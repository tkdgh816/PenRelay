using System;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using Windows.Security.Credentials;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using Windows.Devices.WiFiDirect;
using Windows.Networking;

namespace PenRelay.Desktop;

internal sealed class WifiDirectHost : IDisposable
{
  private readonly WiFiDirectAdvertisementPublisher publisher = new();
  private readonly WiFiDirectConnectionListener listener = new();
  private readonly Channel<WiFiDirectConnectionRequest> requests = Channel.CreateBounded<WiFiDirectConnectionRequest>(1);
  private readonly CancellationTokenSource aborted = new();
  private string? radioError;

  public string ConfigureCode()
  {
    var code = Convert.ToHexString(RandomNumberGenerator.GetBytes(10));
    var suffix = Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(code)))[..8];
    publisher.Advertisement.IsAutonomousGroupOwnerEnabled = true;
    var legacy = publisher.Advertisement.LegacySettings;
    legacy.Ssid = "DIRECT-PR-" + suffix;
    legacy.Passphrase = new PasswordCredential { Password = code };
    legacy.IsEnabled = true;
    return code;
  }

  public async Task Run(CancellationToken stop, Func<string, CancellationToken, Task<bool>> confirm,
      Action<string> report, Func<IPAddress, CancellationToken, Task> serve)
  {
    using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(stop, aborted.Token);
    var token = lifetime.Token;
    publisher.StatusChanged += StatusChanged;
    listener.ConnectionRequested += Requested;
    publisher.Start();
    try
    {
      while (!token.IsCancellationRequested)
      {
        report("Wi-Fi Direct · 태블릿에서 PC 연결 QR을 스캔하거나 코드를 입력하세요.");
        using var request = await requests.Reader.ReadAsync(token);
        var info = request.DeviceInformation;
        if (!await confirm(info.Name, token)) continue;
        WiFiDirectDevice connected;
        try
        {
          connected = await WiFiDirectDevice.FromIdAsync(info.Id).AsTask(token)
              ?? throw new InvalidOperationException("코드로 접속한 기기를 열 수 없습니다.");
        }
        catch (Exception error) when (!token.IsCancellationRequested)
        {
          report("무선 연결 실패 · " + error.Message);
          continue;
        }
        using var peer = connected;
        var local = peer.GetConnectionEndpointPairs().FirstOrDefault(p => p.LocalHostName.Type == HostNameType.Ipv4);
        if (local == null) throw new InvalidOperationException("Wi-Fi Direct의 PC IPv4 주소를 얻지 못했습니다.");
        using var link = CancellationTokenSource.CreateLinkedTokenSource(token);
        void Changed(WiFiDirectDevice device, object args)
        {
          if (device.ConnectionStatus == WiFiDirectConnectionStatus.Disconnected) link.Cancel();
        }
        peer.ConnectionStatusChanged += Changed;
        try
        {
          if (peer.ConnectionStatus == WiFiDirectConnectionStatus.Connected)
            await serve(IPAddress.Parse(local.LocalHostName.CanonicalName), link.Token);
        }
        finally { peer.ConnectionStatusChanged -= Changed; }
      }
    }
    catch (OperationCanceledException) when (token.IsCancellationRequested) { }
    if (radioError != null) throw new InvalidOperationException(radioError);
  }

  private void Requested(WiFiDirectConnectionListener sender, WiFiDirectConnectionRequestedEventArgs args)
  {
    var request = args.GetConnectionRequest();
    if (!requests.Writer.TryWrite(request)) request.Dispose();
  }

  private void StatusChanged(WiFiDirectAdvertisementPublisher sender, WiFiDirectAdvertisementPublisherStatusChangedEventArgs args)
  {
    if (args.Status != WiFiDirectAdvertisementPublisherStatus.Aborted) return;
    radioError = "Wi-Fi Direct 대기 실패 · 무선 어댑터와 드라이버, 모바일 핫스팟 상태를 확인하세요. " + args.Error;
    aborted.Cancel();
  }

  public void Dispose()
  {
    listener.ConnectionRequested -= Requested;
    publisher.StatusChanged -= StatusChanged;
    requests.Writer.TryComplete();
    while (requests.Reader.TryRead(out var pending)) pending.Dispose();
    if (publisher.Status == WiFiDirectAdvertisementPublisherStatus.Started) publisher.Stop();
  }
}
