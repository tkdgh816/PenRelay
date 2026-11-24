using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using PenRelay.Core;

namespace PenRelay.SessionFixture;

[System.Runtime.Versioning.SupportedOSPlatform("windows")]
public static class LanHost
{
  public static string TrustDirectory => Path.Combine(Environment.GetFolderPath(
    Environment.SpecialFolder.LocalApplicationData), "PenRelay", "trust");

  public static async Task Run(IPAddress address, int sessionPort, int pairPort, bool temporary, PenMonitor? target = null, CanvasReplica? replica = null, Func<Task<string?>>? read = null, Action<string>? report = null, bool slowCanvas = true, HostTelemetry? telemetry = null, DeviceTrust? sharedTrust = null)
  {
    if (address.Equals(IPAddress.Any) || address.Equals(IPAddress.IPv6Any))
      throw new ArgumentException("대기할 PC의 개별 LAN 주소를 지정하세요.");
    var directory = temporary ? Directory.CreateTempSubdirectory("penrelay-lan-").FullName :
      TrustDirectory;
    string keyName = temporary ? "PenRelay.Test." + Guid.NewGuid().ToString("N") : "PenRelay.Host.v1";
    try
    {
      using var identity = UserCertificate(keyName);
      if (temporary)
      {
        using var reopened = UserCertificate(keyName);
        if (TrustedConnections.Fingerprint(identity) != TrustedConnections.Fingerprint(reopened))
          throw new InvalidOperationException("저장된 PC 키 지문 불일치");
      }
      using var ownedTrust = sharedTrust == null ? new DeviceTrust(directory) : null;
      await Serve(sharedTrust ?? ownedTrust!, address, sessionPort, pairPort, identity, target, replica, temporary && slowCanvas, read ?? Console.In.ReadLineAsync, report ?? Console.WriteLine, telemetry);
    }
    finally
    {
      if (temporary)
      {
        if (CngKey.Exists(keyName))
        {
          using var key = CngKey.Open(keyName);
          key.Delete();
        }
        File.Delete(Path.Combine(directory, "trusted-keys.txt"));
        File.Delete(Path.Combine(directory, "owner.lock"));
        Directory.Delete(directory);
      }
    }
  }

  private static async Task Serve(DeviceTrust trust, IPAddress address, int sessionPort, int pairPort, X509Certificate2 identity, PenMonitor? target, CanvasReplica? replica, bool slowCanvas, Func<Task<string?>> read, Action<string> report, HostTelemetry? telemetry)
  {
    var transport = new TrustedConnections(trust, identity);
    using var stop = new CancellationTokenSource();
    using var sessions = new TcpListener(address, sessionPort);
    using var pairs = new TcpListener(address, pairPort);
    void StartListener(TcpListener listener, string label, int port)
    {
      try { listener.Start(); }
      catch (SocketException ex) when (ex.SocketErrorCode == SocketError.AddressAlreadyInUse)
      {
        throw new IOException($"{label} {port}번은 이미 사용 중입니다. 기기 연결에서 다른 포트를 입력해 주세요.", ex);
      }
      catch (SocketException ex) when (ex.SocketErrorCode == SocketError.AddressNotAvailable)
      {
        throw new IOException($"PC 대기 주소 {address}을(를) 사용할 수 없습니다. 목록을 새로 고친 뒤 주소를 다시 선택해 주세요.", ex);
      }
    }
    StartListener(sessions, "세션 포트", sessionPort);
    StartListener(pairs, "등록 포트", pairPort);
    var gate = new object();
    string? candidate = null;
    TaskCompletionSource<bool>? decision = null;
    TrustedSession? active = null;
    TaskCompletionSource? resume = null;
    bool targetChanged = false;
    async Task<bool> Confirm(string pin, CancellationToken token)
    {
      var answer = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
      lock (gate) { candidate = pin; decision = answer; }
      report("승인대기 " + pin);
      try { return await answer.Task.WaitAsync(token); }
      finally { lock (gate) { if (decision == answer) { decision = null; candidate = null; } } }
    }
    async Task Pair(TcpClient socket, CancellationToken token)
    {
      var result = await transport.HandleRegistrationAsync(socket, Confirm, token);
      if (result != TrustedConnections.RegistrationOutcome.StatusChecked)
        report(result == TrustedConnections.RegistrationOutcome.Registered ? "등록 완료" : "등록 거부");
    }
    async Task Session(TcpClient socket, CancellationToken token)
    {
      Task? paused;
      lock (gate) paused = resume?.Task;
      if (paused != null) await paused.WaitAsync(token);
      report("인증 중");
      using var session = await transport.AcceptSessionAsync(socket, token);
      lock (gate) active = session;
      report("연결 " + session.Id);
      telemetry?.Connected(session.PeerFingerprint,session.PeerName,session.SupportsPages);
      void RunSession()
      {
        using var library = session.SupportsPages ? telemetry?.OpenLibrary?.Invoke(session.PeerFingerprint) : null;
        void SlowCanvasReader(Frame frame)
        {
          if (frame.Kind == FrameKind.CommitBegin)
          {
            using var json = System.Text.Json.JsonDocument.Parse(frame.Payload);
            var b = json.RootElement;
            report($"전송 {b.GetProperty("newRev").GetString()} {b.GetProperty("full").GetBoolean()} {b.GetProperty("byteLength").GetInt32()}");
          }
          if (frame.Kind != FrameKind.Chunk) return;
          if (System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(frame.Payload.AsSpan(16)) == 0) report("첫조각");
          Thread.Sleep(20);
        }
        if (target == null)
        {
          SessionControl.Run(session, stop.Token, replica: replica,
            afterCanvasFrame: slowCanvas && replica != null ? SlowCanvasReader : null);
          return;
        }
        var pen = new DigitizerPen(new MonitorPen(target), telemetry == null ? null : telemetry.Observe);
        void CheckTarget()
        {
          targetChanged |= !target.Current();
          if (targetChanged) throw new MonitorChangedException();
        }
        void Release(string reason)
        {
          bool held = pen.InContact;
          pen.Reset();
          if (held) report("접촉 해제 " + reason);
        }
        try
        {
          void AcceptPen(Frame frame)
          {
            var start = System.Diagnostics.Stopwatch.GetTimestamp();
            pen.Accept(frame);
            telemetry?.Batch(System.Diagnostics.Stopwatch.GetElapsedTime(start).TotalMilliseconds);
          }
          try { SessionControl.Run(session, stop.Token, telemetry == null ? pen.Accept : AcceptPen, endDigitizer: () => Release("모드 전환"), target: target.Bounds, checkTarget: CheckTarget, replica: replica, modeChanged: telemetry?.ModeChanged, mapping: telemetry?.Mapping, library: library); }
          finally { Release("세션 종료"); }
        }
        catch (MonitorChangedException) { targetChanged = true; throw; }
      }
      try { await Task.Run(RunSession); }
      finally { replica?.Disconnect(); lock (gate) active = null; report("종료 " + session.Id); }
    }
    async Task Listen(TcpListener listener, Func<TcpClient, CancellationToken, Task> handle, TimeSpan limit)
    {
      while (!stop.IsCancellationRequested)
      {
        try
        {
          using var socket = await listener.AcceptTcpClientAsync(stop.Token);
          using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
          timeout.CancelAfter(limit);
          await handle(socket, timeout.Token);
        }
        catch (Exception error) when (error is IOException or SocketException or OperationCanceledException
          or ObjectDisposedException or System.Security.Authentication.AuthenticationException
          or InvalidDataException or System.Text.Json.JsonException or KeyNotFoundException or InvalidOperationException
          or Microsoft.Data.Sqlite.SqliteException or FormatException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        { if (!stop.IsCancellationRequested) report($"연결 정리 {error.GetType().Name} {error.Message}"); }
      }
    }
    var accepting = Task.WhenAll(Listen(sessions, Session, TimeSpan.FromSeconds(5)),
      Listen(pairs, Pair, TimeSpan.FromSeconds(125)));
    report($"준비 {((IPEndPoint)sessions.LocalEndpoint).Port} {((IPEndPoint)pairs.LocalEndpoint).Port} {TrustedConnections.Fingerprint(identity)}");
    report("명령: invite, approve <기기 지문>, cancel, list, disconnect, revoke <지문>, stop");
    try
    {
      while (await read() is { } line && line != "stop")
      {
        switch (line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
          case ["invite"]:
            lock (gate) decision?.TrySetResult(false);
            report("초대 " + Convert.ToBase64String(trust.OpenPairing().Secret));
            break;
          case ["approve", var pin]:
            lock (gate)
            {
              if (candidate == pin) decision?.TrySetResult(true);
              else report("승인 대기 지문 불일치");
            }
            break;
          case ["cancel"]:
            trust.CancelPairing();
            lock (gate) decision?.TrySetResult(false);
            break;
          case ["list"]: foreach (var key in trust.RegisteredKeys()) report("기기 " + key); break;
          case ["disconnect"]: lock (gate) active?.Dispose(); break;
          case ["pause"] when replica != null:
            lock (gate) {
              resume ??= new(TaskCreationOptions.RunContinuationsAsynchronously);
              active?.Dispose();
            }
            break;
          case ["resume"] when replica != null:
            lock (gate) { resume?.TrySetResult(); resume = null; }
            break;
          case ["revoke", var key]: report(trust.Revoke(key) ? "폐기 완료" : "등록되지 않은 키"); break;
          default: report("알 수 없는 명령"); break;
        }
      }
    }
    finally { trust.CancelPairing(); stop.Cancel(); await accepting; }
  }

  private static X509Certificate2 UserCertificate(string name)
  {
    var provider = CngProvider.MicrosoftSoftwareKeyStorageProvider;
    using var key = CngKey.Exists(name, provider) ? CngKey.Open(name, provider) :
      CngKey.Create(CngAlgorithm.ECDsaP256, name, new CngKeyCreationParameters
      { Provider = provider, ExportPolicy = CngExportPolicies.None, KeyUsage = CngKeyUsages.Signing });
    using var signing = new ECDsaCng(key);
    var request = new CertificateRequest("CN=Pen Relay", signing, HashAlgorithmName.SHA256);
    return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddYears(1));
  }
}
