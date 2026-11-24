using System.Security.Cryptography;

namespace PenRelay.Core;

// 한 Windows 호스트가 소유하는 등록 목록. 디스크에는 SHA-256 공개키 지문만 기록한다.
public sealed class DeviceTrust : IDisposable
{
  public static readonly TimeSpan PairingLifetime = TimeSpan.FromMinutes(2);
  public const int FailureLimit = 5;
  private readonly object gate = new();
  private readonly TimeProvider clock;
  private readonly string path;
  private readonly FileStream owner;
  private readonly Dictionary<string, Registration> peers = new(StringComparer.Ordinal);
  private Window? window;
  private Lease? active;
  private bool disposed;

  public DeviceTrust(string directory, TimeProvider? clock = null)
  {
    this.clock = clock ?? TimeProvider.System;
    Directory.CreateDirectory(directory);
    path = Path.Combine(directory, "trusted-keys.txt");
    owner = new FileStream(Path.Combine(directory, "owner.lock"), FileMode.OpenOrCreate,
      FileAccess.ReadWrite, FileShare.None);
    try
    {
      if (File.Exists(path))
        foreach (var pin in File.ReadAllLines(path))
        {
          ValidatePin(pin);
          peers.Add(pin, new Registration(pin));
        }
    }
    catch { owner.Dispose(); throw; }
  }

  public sealed class Invitation(byte[] secret, DateTimeOffset expiresAt)
  {
    public byte[] Secret { get; } = secret;
    public DateTimeOffset ExpiresAt { get; } = expiresAt;
  }

  private sealed class Window(byte[] digest, long started)
  {
    public byte[] Digest { get; } = digest;
    public long Started { get; } = started;
    public int Failures;
    public string? Candidate;
  }

  public Invitation OpenPairing()
  {
    lock (gate)
    {
      ObjectDisposedException.ThrowIf(disposed, this);
      byte[] secret = RandomNumberGenerator.GetBytes(32);
      window = new Window(SHA256.HashData(secret), clock.GetTimestamp());
      return new Invitation(secret, clock.GetUtcNow() + PairingLifetime);
    }
  }

  public void CancelPairing() { lock (gate) window = null; }

  public string[] RegisteredKeys()
  {
    lock (gate)
    {
      ObjectDisposedException.ThrowIf(disposed, this);
      return peers.Keys.Order(StringComparer.Ordinal).ToArray();
    }
  }

  public async Task<bool> RegisterAsync(ReadOnlyMemory<byte> secret, string pin,
    Func<string, CancellationToken, Task<bool>> confirm, CancellationToken cancellation = default)
  {
    ValidatePin(pin);
    Window pending;
    TimeSpan remaining;
    lock (gate)
    {
      if (window == null || !IsCurrent(window) || window.Candidate != null) return false;
      pending = window;
      if (secret.Length != 32 || !CryptographicOperations.FixedTimeEquals(SHA256.HashData(secret.Span), pending.Digest))
      {
        if (++pending.Failures >= FailureLimit) window = null;
        return false;
      }
      pending.Candidate = pin;
      remaining = PairingLifetime - clock.GetElapsedTime(pending.Started);
      if (remaining <= TimeSpan.Zero) { window = null; return false; }
    }

    using var deadline = new CancellationTokenSource(remaining, clock);
    using var linked = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token, cancellation);
    try
    {
      bool approved = await confirm(pin, linked.Token).WaitAsync(linked.Token).ConfigureAwait(false);
      lock (gate)
      {
        if (!approved || linked.IsCancellationRequested || !IsCurrent(pending)) return false;
        if (peers.TryGetValue(pin, out var existing))
        {
          return !existing.Disabled;
        }
        Save(peers.Keys.Append(pin));
        peers.Add(pin, new Registration(pin));
        return true;
      }
    }
    catch (OperationCanceledException) when (linked.IsCancellationRequested) { return false; }
    finally { lock (gate) { if (ReferenceEquals(window, pending)) window = null; } }
  }

  private bool IsCurrent(Window pending) =>
    ReferenceEquals(window, pending) && clock.GetElapsedTime(pending.Started) < PairingLifetime;

  public bool Revoke(string pin)
  {
    lock (gate)
    {
      ObjectDisposedException.ThrowIf(disposed, this);
      if (window?.Candidate == pin) window = null;
      if (!peers.TryGetValue(pin, out var peer)) return false;
      // 먼저 세션을 닫는다. 영속화 실패는 호출자에게 전달하여 재시도할 수 있게 한다.
      peer.Disabled = true;
      if (active?.Peer == peer) Disconnect(active);
      Save(peers.Keys.Where(key => key != pin));
      peers.Remove(pin);
      return true;
    }
  }

  internal sealed class Registration(string pin)
  {
    internal string Pin { get; } = pin;
    internal bool Disabled;
  }

  internal Registration? Find(string pin)
  {
    lock (gate) return peers.TryGetValue(pin, out var peer) && !peer.Disabled && !disposed ? peer : null;
  }

  // TLS 검증과 세션 인계 사이에 폐기 후 같은 키가 재등록되어도 이전 연결을 살리지 않는다.
  internal Lease? Attach(Registration peer, Action abort)
  {
    lock (gate)
    {
      if (active != null || Find(peer.Pin) != peer) return null;
      return active = new Lease(this, peer, abort);
    }
  }

  internal sealed class Lease(DeviceTrust trust, Registration peer, Action abort) : IDisposable
  {
    internal Registration Peer { get; } = peer;
    internal Action Abort { get; } = abort;
    // 처리기는 짧은 동기 상태 변경만 수행한다. 블로킹 I/O는 gate 밖에서 한다.
    internal bool Dispatch(Action consume)
    {
      lock (trust.gate)
      {
        if (trust.active != this) return false;
        consume();
        return true;
      }
    }
    public void Dispose() { lock (trust.gate) trust.Disconnect(this); }
  }

  private void Disconnect(Lease lease)
  {
    if (active != lease) return;
    active = null;
    lease.Abort();
  }

  private void Save(IEnumerable<string> pins)
  {
    var temporary = path + ".tmp";
    try
    {
      File.WriteAllLines(temporary, pins.Order(StringComparer.Ordinal));
      File.Move(temporary, path, overwrite: true);
    }
    finally { File.Delete(temporary); }
  }

  private static void ValidatePin(string pin)
  {
    if (pin.Length != 64 || !pin.All(char.IsAsciiHexDigitUpper))
      throw new ArgumentException("SHA-256 대문자 16진 지문이 필요합니다.", nameof(pin));
  }

  public void Dispose()
  {
    lock (gate)
    {
      if (disposed) return;
      disposed = true;
      window = null;
      if (active != null) Disconnect(active);
      owner.Dispose();
    }
  }
}
