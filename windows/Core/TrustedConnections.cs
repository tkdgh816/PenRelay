using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PenRelay.Core;

public sealed class TrustedConnections(DeviceTrust trust, X509Certificate2 hostIdentity)
{
  private static readonly SslApplicationProtocol StatusProtocol = new("penrelay-status/1");
  public static string Fingerprint(X509Certificate2 cert) =>
    Convert.ToHexString(SHA256.HashData(cert.PublicKey.ExportSubjectPublicKeyInfo()));

  private SslServerAuthenticationOptions Options(Func<string, bool> accept) => new()
  {
    ServerCertificate = hostIdentity,
    EnabledSslProtocols = SslProtocols.Tls13,
    ClientCertificateRequired = true,
    AllowTlsResume = false,
    RemoteCertificateValidationCallback = (_, certificate, _, _) =>
    {
      if (certificate is not X509Certificate2 leaf) return false;
      var now = DateTime.UtcNow;
      if (now < leaf.NotBefore.ToUniversalTime() || now > leaf.NotAfter.ToUniversalTime()) return false;
      return accept(Fingerprint(leaf));
    }
  };

  // 전용 등록 포트는 32바이트 비밀 한 개만 받고 결과 한 바이트를 돌려준 뒤 닫는다.
  public async Task<bool> PairAsync(TcpClient socket, Func<string, CancellationToken, Task<bool>> confirm,
    CancellationToken cancellation)
    => await HandleRegistrationAsync(socket, confirm, cancellation) == RegistrationOutcome.Registered;

  public enum RegistrationOutcome { Registered, Denied, StatusChecked }

  public async Task<RegistrationOutcome> HandleRegistrationAsync(TcpClient socket,
    Func<string, CancellationToken, Task<bool>> confirm, CancellationToken cancellation)
  {
    using (socket)
    using (var tls = new SslStream(socket.GetStream()))
    {
      string? presented = null;
      var options = Options(pin => { presented = pin; return true; });
      options.ApplicationProtocols = [StatusProtocol];
      await tls.AuthenticateAsServerAsync(options, cancellation);
      if (!tls.IsMutuallyAuthenticated || presented == null) throw new AuthenticationException("기기 키 확인 실패");
      if (tls.NegotiatedApplicationProtocol == StatusProtocol)
      {
        // 질의 대상은 TLS에서 소유권을 입증한 자기 키로 한정한다.
        await tls.WriteAsync(new byte[] { trust.Find(presented) != null ? (byte)1 : (byte)0 }, cancellation);
        return RegistrationOutcome.StatusChecked;
      }
      byte[] secret = new byte[32];
      bool accepted;
      try
      {
        await tls.ReadExactlyAsync(secret, cancellation);
        accepted = await trust.RegisterAsync(secret, presented, confirm, cancellation);
      }
      finally { CryptographicOperations.ZeroMemory(secret); }
      await tls.WriteAsync(new byte[] { accepted ? (byte)1 : (byte)0 }, cancellation);
      return accepted ? RegistrationOutcome.Registered : RegistrationOutcome.Denied;
    }
  }

  public async Task<TrustedSession> AcceptSessionAsync(TcpClient socket, CancellationToken cancellation)
  {
    var tls = new SslStream(socket.GetStream());
    try
    {
      DeviceTrust.Registration? peer = null;
      await tls.AuthenticateAsServerAsync(Options(pin => (peer = trust.Find(pin)) != null), cancellation);
      if (!tls.IsMutuallyAuthenticated || peer == null) throw new AuthenticationException("미등록 기기");
      var lease = trust.Attach(peer, socket.Dispose) ?? throw new AuthenticationException("폐기 또는 사용 중인 세션");
      try { return await TrustedSession.NegotiateAsync(tls, lease, cancellation); }
      catch { lease.Dispose(); throw; }
    }
    catch
    {
      socket.Dispose();
      tls.Dispose();
      throw;
    }
  }
}

