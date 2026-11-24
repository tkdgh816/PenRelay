using System;
using System.Net;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

using PenRelay.Core;
using PenRelay.SessionFixture;

namespace PenRelay.Desktop;

internal sealed class HostSession
{
  private readonly DeviceTrust trust;
  public CanvasReplica Replica { get; } = new();
  public HostTelemetry Telemetry { get; }
  public int SessionPort { get; }
  public int PairingPort { get; }
  private readonly Channel<string> commands = Channel.CreateUnbounded<string>();
  private readonly CancellationTokenSource stop = new();
  public HostSession(Action<string> modeChanged, Action<string, string, bool> connected, PenMappingOptions mapping,
      Func<string, PageLibrary> openLibrary, int sessionPort, int pairingPort, DeviceTrust trust)
  {
    this.trust = trust;
    SessionPort = sessionPort;
    PairingPort = pairingPort;
    int calibrationPending = mapping.Calibrate ? 1 : 0;
    Telemetry = new()
    {
      ModeChanged = modeChanged,
      PeerConnected = connected,
      OpenLibrary = openLibrary,
      Mapping = () => mapping with { Calibrate = Interlocked.Exchange(ref calibrationPending, 0) != 0 }
    };
  }
  public void Command(string value) => commands.Writer.TryWrite(value);
  public void Stop()
  {
    commands.Writer.TryComplete();
    stop.Cancel();
  }
  public CancellationToken Token => stop.Token;
  public Task Serve(IPAddress address, PenMonitor monitor, Action<string> report, CancellationToken token)
  {
    async Task<string?> Read()
    {
      try { return await commands.Reader.ReadAsync(token); }
      catch (ChannelClosedException) { return null; }
      catch (OperationCanceledException) when (token.IsCancellationRequested) { return null; }
    }
    return LanHost.Run(address, SessionPort, PairingPort, false, monitor, Replica, Read, report,
        slowCanvas: false, telemetry: Telemetry, sharedTrust: trust);
  }

  public async Task Run(ConnectionRoute route, IPAddress address, PenMonitor monitor, string? adbPath, Action<string> report)
  {
    UsbPortBridge? bridge = null;
    Task watch = Task.CompletedTask;
    try
    {
      if (route == ConnectionRoute.Reverse)
      {
        bridge = new(adbPath, SessionPort, PairingPort);
        await bridge.Ensure();
        watch = bridge.Monitor(stop.Token, report);
      }
      if (!stop.IsCancellationRequested)
      {
        await Serve(address, monitor, report, stop.Token);
      }
    }
    finally
    {
      stop.Cancel();
      try
      {
        await watch;
      }
      catch (OperationCanceledException) { }
      if (bridge != null)
      {
        await bridge.Close();
      }
    }
  }
}
