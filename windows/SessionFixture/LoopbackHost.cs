using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using PenRelay.Core;

namespace PenRelay.SessionFixture;

// JVM이 파이프로 PC 승인과 폐기를 제어한다. 신뢰 저장소는 실행마다 격리한다.
internal static class LoopbackHost
{
  public static async Task RunAsync()
  {
    using var server = IssueCertificate("localhost", server: true);
    using var client = IssueCertificate("P1 fixture client", server: false);
    using var stranger = IssueCertificate("P1 unregistered client", server: false);
    using var clientKey = client.GetECDsaPrivateKey()!;
    var expiredRequest = new CertificateRequest("CN=P1 expired client", clientKey, HashAlgorithmName.SHA256);
    using var expired = expiredRequest.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-2), DateTimeOffset.UtcNow.AddDays(-1));
    var directory = Directory.CreateTempSubdirectory("penrelay-pairing-");
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(45));
    var token = deadline.Token;
    using var pairing = new TcpListener(IPAddress.Loopback, 0);
    using var listener = new TcpListener(IPAddress.Loopback, 0);
    pairing.Start();
    listener.Start();
    string pin = TrustedConnections.Fingerprint(client);
    try
    {
      using (var trust = new DeviceTrust(directory.FullName))
      {
        var host = new TrustedConnections(trust, server);
        var invite = trust.OpenPairing();
        Console.WriteLine(((IPEndPoint)pairing.LocalEndpoint).Port);
        Console.WriteLine(((IPEndPoint)listener.LocalEndpoint).Port);
        Console.WriteLine(TrustedConnections.Fingerprint(server));
        Console.WriteLine(Convert.ToBase64String(client.Export(X509ContentType.Pkcs12, "")));
        Console.WriteLine(Convert.ToBase64String(stranger.Export(X509ContentType.Pkcs12, "")));
        Console.WriteLine(Convert.ToBase64String(expired.Export(X509ContentType.Pkcs12, "")));
        Console.WriteLine(Convert.ToBase64String(invite.Secret));
        Console.Out.Flush();
        await ExpectDenied(host, listener, token);
        Task<bool> UnexpectedApproval(string _, CancellationToken __) =>
          throw new InvalidOperationException("잘못된 비밀 또는 재사용에 승인 요청 발생");
        // 잘못된 PC 지문: TLS 합의가 실패하여 비밀을 받을 수 없다.
        try
        {
          await host.PairAsync(await pairing.AcceptTcpClientAsync(token), UnexpectedApproval, token);
          throw new InvalidOperationException("PC 지문 불일치 연결이 등록 단계에 도달");
        }
        catch (Exception e) when (e is AuthenticationException or IOException) { }
        if (await host.PairAsync(await pairing.AcceptTcpClientAsync(token), UnexpectedApproval, token))
          throw new InvalidOperationException("잘못된 비밀 수락");
        async Task<bool> Confirm(string candidate, CancellationToken ct)
        {
          Console.WriteLine("confirm " + candidate);
          return await Console.In.ReadLineAsync(ct) == "approve " + candidate;
        }
        if (!await host.PairAsync(await pairing.AcceptTcpClientAsync(token), Confirm, token))
          throw new InvalidOperationException("기기 등록 실패");
        if (await host.PairAsync(await pairing.AcceptTcpClientAsync(token), UnexpectedApproval, token))
          throw new InvalidOperationException("일회용 비밀 재사용");
        pairing.Stop();
      }
      using (var trust = new DeviceTrust(directory.FullName))
      {
        var host = new TrustedConnections(trust, server);
        if (!trust.RegisteredKeys().SequenceEqual([pin])) throw new InvalidOperationException("등록 목록 복원 실패");
        await ExpectDenied(host, listener, token); // 인증서 없음
        await ExpectDenied(host, listener, token); // 미등록 기기
        await ExpectDenied(host, listener, token); // 등록된 공개키와 같은 만료 인증서
        Guid firstId;
        using (var first = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token))
        {
          firstId = Exchange(first);
          var batch = new[] { Expect(first, FrameKind.Ping, firstId, 2), Expect(first, FrameKind.Ping, firstId, 3) };
          foreach (var ping in batch) ReplyToPing(first, ping);
          Expect(first, FrameKind.Close, firstId, 4);
        }
        await CheckProtocolFailures(host, listener, token);
        await CheckClientFailures(listener, server, pin, firstId, token);
        await CheckRecovery(host, listener, token);
        using var active = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token);
        Exchange(active);
        // 양단의 입력이 멈춘 상태에서 PC 관리 경로가 소켓을 닫아야 한다.
        var receive = Task.Run(() =>
        {
          try { active.Receive(_ => throw new InvalidOperationException("추가 프레임 수락")); }
          catch (Exception e) when (e is IOException or ObjectDisposedException or SocketException) { }
        });
        if (await Console.In.ReadLineAsync(token) != "revoke" || !trust.Revoke(pin))
          throw new InvalidOperationException("폐기 실패");
        Console.WriteLine("revoked");
        await receive.WaitAsync(token);
        if (await Console.In.ReadLineAsync(token) != "closed") throw new IOException("클라이언트 종료 확인 실패");
        await ExpectDenied(host, listener, token);
      }
      using var reopened = new DeviceTrust(directory.FullName);
      if (reopened.RegisteredKeys().Length != 0) throw new InvalidOperationException("폐기된 키 복원");
      await ExpectDenied(new TrustedConnections(reopened, server), listener, token);
    }
    finally
    {
      File.Delete(Path.Combine(directory.FullName, "trusted-keys.txt"));
      File.Delete(Path.Combine(directory.FullName, "owner.lock"));
      directory.Delete();
    }
  }

  private static async Task ExpectDenied(TrustedConnections host, TcpListener listener, CancellationToken token)
  {
    try { using var unexpected = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token); }
    catch (AuthenticationException) { return; }
    throw new InvalidOperationException("미인증 세션 수락");
  }

  private static Guid Exchange(TrustedSession stream)
  {
    ReplyToPing(stream, Expect(stream, FrameKind.Ping, stream.Id, 1));
    return stream.Id;
  }

  private static void ReplyToPing(TrustedSession stream, Frame ping)
  {
    string t2 = NowUs().ToString(System.Globalization.CultureInfo.InvariantCulture);
    using var request = JsonDocument.Parse(ping.Payload);
    stream.Send(FrameKind.Pong, JsonSerializer.SerializeToUtf8Bytes(new
    {
      nonce = request.RootElement.GetProperty("nonce").GetString(),
      t1 = request.RootElement.GetProperty("t1").GetString(),
      t2, t3 = NowUs().ToString(System.Globalization.CultureInfo.InvariantCulture)
    }));
  }

  private static Frame Expect(TrustedSession stream, FrameKind kind, Guid id, ulong sequence)
  {
    Frame? frame = null;
    if (!stream.Receive(received => frame = received) || frame == null) throw new EndOfStreamException();
    if (frame.Kind != kind || frame.SessionId != id || frame.Sequence != sequence)
      throw new InvalidDataException("종류·세션·순서 불일치");
    return frame;
  }

  private static ulong NowUs() => (ulong)Stopwatch.GetElapsedTime(0).Ticks / 10;

  private static async Task CheckProtocolFailures(TrustedConnections host, TcpListener listener, CancellationToken token)
  {
    foreach (string scenario in new[] { "major", "minor", "versions", "first-frame", "old-id", "duplicate", "gap", "second-hello" })
    {
      TrustedSession? session = null;
      try
      {
        session = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token);
        if (scenario is "major" or "minor" or "versions" or "first-frame")
          throw new InvalidOperationException("잘못된 협상 수락: " + scenario);
        if (scenario == "duplicate") Expect(session, FrameKind.Ping, session.Id, 1);
        session.Receive(_ => throw new InvalidOperationException("위반 프레임 처리기 호출: " + scenario));
        throw new InvalidOperationException("프로토콜 오류 누락: " + scenario);
      }
      catch (InvalidDataException)
      {
        // 시험 코드가 Dispose하기 전에 상대가 실제 종료를 관찰해야 한다.
        if (await Console.In.ReadLineAsync(token) != "closed " + scenario) throw new IOException("종료 확인 실패");
      }
      finally { session?.Dispose(); }
    }
  }

  // Android 수신 경로 시험용 상대. 등록된 시험 기기의 TLS 연결에 정해진 응답만 보낸다.
  private static async Task CheckClientFailures(TcpListener listener, X509Certificate2 identity, string pin,
    Guid previousId, CancellationToken token)
  {
    var options = new SslServerAuthenticationOptions
    {
      ServerCertificate = identity,
      EnabledSslProtocols = SslProtocols.Tls13,
      ClientCertificateRequired = true,
      AllowTlsResume = false,
      RemoteCertificateValidationCallback = (_, cert, _, _) =>
        cert is X509Certificate2 leaf && TrustedConnections.Fingerprint(leaf) == pin
    };
    foreach (string scenario in new[] { "major", "minor", "body", "zero-id", "welcome-sequence", "old-id", "duplicate", "gap", "second-welcome" })
    {
      using var socket = await listener.AcceptTcpClientAsync(token);
      using var tls = new SslStream(socket.GetStream());
      await tls.AuthenticateAsServerAsync(options, token);
      var hello = FrameCodec.Read(tls);
      if (hello is not { Kind: FrameKind.Hello, Sequence: 0 } || hello.SessionId != Guid.Empty)
        throw new InvalidDataException("Android Hello 오류");
      var id = Guid.NewGuid();
      byte[] body = scenario == "body" ? "{}"u8.ToArray() : JsonSerializer.SerializeToUtf8Bytes(new
      {
        major = scenario == "major" ? 2 : 1, minor = scenario == "minor" ? 1 : 0,
        limits = new { maxPayload = FrameCodec.MaxPayload }
      });
      using var bytes = new MemoryStream();
      FrameCodec.Write(bytes, new Frame(FrameKind.Welcome, scenario == "zero-id" ? Guid.Empty : id,
        scenario == "welcome-sequence" ? 1UL : 0UL, NowUs(), body));
      var data = new Frame(FrameKind.Pong, id, 1, NowUs(), "{}"u8.ToArray());
      switch (scenario)
      {
        case "old-id": FrameCodec.Write(bytes, data with { SessionId = previousId }); break;
        case "duplicate":
          FrameCodec.Write(bytes, data);
          FrameCodec.Write(bytes, data);
          break;
        case "gap":
          FrameCodec.Write(bytes, data with { Sequence = 2 });
          bytes.SetLength(bytes.Length - data.Payload.Length);
          break;
        case "second-welcome": FrameCodec.Write(bytes, data with { Kind = FrameKind.Welcome }); break;
      }
      await tls.WriteAsync(bytes.ToArray(), token);
      try
      {
        if (await tls.ReadAsync(new byte[1], token) != 0) throw new InvalidOperationException("위반 후 추가 데이터");
      }
      catch (IOException) { /* 종료가 TCP reset으로 전달될 수도 있다. */ }
      Console.WriteLine("client-closed " + scenario);
    }
  }

  private static async Task CheckRecovery(TrustedConnections host, TcpListener listener, CancellationToken token)
  {
    int pings = 0;
    using (var silent = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token))
    {
      using var bound = token.Register(silent.Dispose);
      try
      {
        while (silent.Receive(frame =>
        {
          if (frame.Kind == FrameKind.Ping) pings++;
        })) { }
      }
      catch (IOException) { }
    }
    if (pings < 2) throw new InvalidOperationException("무응답 시험의 Ping 부족");
    for (int i = 0; i < 2; i++)
    {
      using var session = await host.AcceptSessionAsync(await listener.AcceptTcpClientAsync(token), token);
      int pen = 0, canvas = 0;
      try
      {
        SessionControl.Run(session, token,
          frame => { if (frame.Kind != FrameKind.PenBatch) throw new InvalidOperationException("펜 경로 오류"); pen++; },
          frame => { if (frame.Kind != FrameKind.StrokePreview) throw new InvalidOperationException("Canvas 경로 오류"); canvas++; });
      }
      catch (Exception e) when (e is IOException or ObjectDisposedException or SocketException) { }
      if (pen != (i == 0 ? 1 : 0) || canvas != pen) throw new InvalidOperationException("모드별 전달 횟수 오류");
    }
  }

  private static X509Certificate2 IssueCertificate(string name, bool server)
  {
    using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    var request = new CertificateRequest("CN=" + name, key, HashAlgorithmName.SHA256);
    request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
      new OidCollection { new Oid(server ? "1.3.6.1.5.5.7.3.1" : "1.3.6.1.5.5.7.3.2") }, false));
    var now = DateTimeOffset.UtcNow;
    using var generated = request.CreateSelfSigned(now.AddMinutes(-1), now.AddMinutes(5));
    // Schannel이 읽을 수 있는 수명 한정 키 컨테이너를 사용한다.
    return X509CertificateLoader.LoadPkcs12(generated.Export(X509ContentType.Pkcs12, ""), "",
      X509KeyStorageFlags.Exportable);
  }
}
