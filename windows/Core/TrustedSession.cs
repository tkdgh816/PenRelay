using System.Diagnostics;
using System.Net.Security;
using System.Text.Json;

namespace PenRelay.Core;

public sealed class TrustedSession : IDisposable
{
  public Guid Id { get; } = Guid.NewGuid();
  public string PeerFingerprint => lease.Peer.Pin;
  private readonly SslStream tls;
  private readonly DeviceTrust.Lease lease;
  private readonly object readGate = new(), writeGate = new();
  private ulong lastReceived, lastSent;
  private int disposed;
  public string PeerName { get; private set; } = "태블릿";
  public bool SupportsStrokeSync { get; private set; }
  public bool SupportsPages { get; private set; }
  public bool SupportsCanvasVisibility { get; private set; }
  internal IReadOnlySet<string> Modes { get; }

  private TrustedSession(SslStream tls, DeviceTrust.Lease lease, HashSet<string> modes) =>
    (this.tls, this.lease, Modes) = (tls, lease, modes);

  internal static async Task<TrustedSession> NegotiateAsync(SslStream tls, DeviceTrust.Lease lease,
    CancellationToken cancellation)
  {
    var hello = await FrameCodec.ReadHandshakeAsync(tls, header =>
    {
      if (header.Kind != FrameKind.Hello || header.SessionId != Guid.Empty || header.Sequence != 0)
        throw new InvalidDataException("첫 프레임은 빈 세션 ID와 순번 0의 Hello여야 합니다.");
    }, cancellation);
    var session = new TrustedSession(tls, lease, ValidateHello(hello.Payload));
    using var metadata = JsonDocument.Parse(hello.Payload);
    var root = metadata.RootElement;
    session.SupportsStrokeSync = root.GetProperty("sensorCaps").EnumerateArray().Any(x => x.ValueKind == JsonValueKind.String && x.GetString() == "stroke-sync-v1");
    session.SupportsPages = root.GetProperty("sensorCaps").EnumerateArray().Any(x => x.ValueKind == JsonValueKind.String && x.GetString() == "pages-v1");
    session.SupportsCanvasVisibility = root.GetProperty("sensorCaps").EnumerateArray().Any(x => x.ValueKind == JsonValueKind.String && x.GetString() == "canvas-visibility-v1");
    if (root.TryGetProperty("deviceName", out var name) && name.ValueKind == JsonValueKind.String)
      session.PeerName = string.Concat(name.GetString()!.Where(c => !char.IsControl(c)).Take(64)).Trim();
    if (string.IsNullOrWhiteSpace(session.PeerName)) session.PeerName = "태블릿";
    var body = JsonSerializer.SerializeToUtf8Bytes(new { major = 1, minor = 0, pc = Environment.MachineName, limits = new { maxPayload = FrameCodec.MaxPayload } });
    using var welcome = new MemoryStream();
    FrameCodec.Write(welcome, new Frame(FrameKind.Welcome, session.Id, 0, NowUs(), body));
    await tls.WriteAsync(welcome.GetBuffer().AsMemory(0, (int)welcome.Length), cancellation);
    return session;
  }

  private static HashSet<string> ValidateHello(byte[] payload)
  {
    try
    {
      using var json = JsonDocument.Parse(payload);
      var root = json.RootElement;
      if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("versions", out var versions)
        || versions.ValueKind != JsonValueKind.Array)
        throw new InvalidDataException("Hello의 버전 목록이 없습니다.");
      if (!root.TryGetProperty("modes", out var modes) || modes.ValueKind != JsonValueKind.Array
        || !root.TryGetProperty("sensorCaps", out var caps) || caps.ValueKind != JsonValueKind.Array)
        throw new InvalidDataException("Hello의 모드 또는 센서 기능 목록이 없습니다.");
      bool common = false;
      foreach (var version in versions.EnumerateArray())
      {
        if (version.ValueKind != JsonValueKind.Object || !ReadVersionByte(version, "major", out byte major)
          || !ReadVersionByte(version, "minor", out byte minor))
          throw new InvalidDataException("Hello의 버전 형식 오류");
        common |= major == 1 && minor == 0;
      }
      if (!common) throw new InvalidDataException("공통 프로토콜 버전 없음");
      var offered = new HashSet<string>(StringComparer.Ordinal);
      foreach (var mode in modes.EnumerateArray())
      {
        if (mode.ValueKind != JsonValueKind.String) throw new InvalidDataException("모드 형식 오류");
        offered.Add(mode.GetString()!);
      }
      return offered;
    }
    catch (JsonException error) { throw new InvalidDataException("Hello JSON 형식 오류", error); }
  }

  private static bool ReadVersionByte(JsonElement version, string name, out byte value)
  {
    value = 0;
    return version.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.Number
      && property.TryGetByte(out value);
  }

  public bool Receive(Action<Frame> consume)
  {
    lock (readGate)
    {
      ObjectDisposedException.ThrowIf(disposed != 0, this);
      try
      {
        var frame = FrameCodec.Read(tls, header =>
        {
          if (header.SessionId != Id) throw new InvalidDataException("세션 ID 불일치");
          if (lastReceived == ulong.MaxValue || header.Sequence != lastReceived + 1)
            throw new InvalidDataException("수신 순번 불일치");
          if (header.Kind is FrameKind.Hello or FrameKind.Welcome)
            throw new InvalidDataException("세션 협상 중복");
        });
        if (frame == null) { Dispose(); return false; }
        lastReceived = frame.Sequence;
        bool delivered = lease.Dispatch(() => consume(frame));
        if (!delivered || frame.Kind == FrameKind.Close) Dispose();
        return delivered;
      }
      catch { Dispose(); throw; }
    }
  }

  public void Send(FrameKind kind, byte[] payload)
  {
    lock (writeGate)
    {
      ObjectDisposedException.ThrowIf(disposed != 0, this);
      try
      {
        if (kind is FrameKind.Hello or FrameKind.Welcome) throw new InvalidDataException("세션 협상 중복");
        var sequence = checked(lastSent + 1);
        FrameCodec.Write(tls, new Frame(kind, Id, sequence, NowUs(), payload));
        lastSent = sequence;
        if (kind == FrameKind.Close) Dispose();
      }
      catch { Dispose(); throw; }
    }
  }

  private static ulong NowUs() => (ulong)Stopwatch.GetElapsedTime(0).Ticks / 10;

  public void Dispose()
  {
    if (Interlocked.Exchange(ref disposed, 1) != 0) return;
    lease.Dispose();
    tls.Dispose();
  }
}
