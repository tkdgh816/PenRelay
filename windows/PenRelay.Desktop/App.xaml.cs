using System;
using System.Linq;
using System.Runtime.InteropServices;

using Microsoft.Windows.AppLifecycle;

using PenRelay.SessionFixture;

using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

public partial class App : Application
{
  private Window? window;
  public static string ProductName => System.Globalization.CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ko" ? "펜 릴레이" : "Pen Relay";

  public App() => InitializeComponent();

  [DllImport("user32.dll")]
  private static extern bool AllowSetForegroundWindow(uint processId);

  protected override async void OnLaunched(LaunchActivatedEventArgs args)
  {
    var command = System.Environment.GetCommandLineArgs();
    if (command is [_, "--canvas", var address])
    {
      window = new CanvasWindow(System.Net.IPAddress.Parse(address));
      window.Activate();
      return;
    }
    if (command is [_, var testMode, var testAddress] && testMode is ("--canvas-test" or "--p4-test") && System.Console.IsInputRedirected && System.Console.IsOutputRedirected)
    {
      System.Console.OutputEncoding = new System.Text.UTF8Encoding(false);
      System.Console.InputEncoding = new System.Text.UTF8Encoding(false);
      window = new CanvasWindow(System.Net.IPAddress.Parse(testAddress), 0, 0, fixture: true, benchmark: testMode == "--p4-test");
      window.Activate();
      return;
    }
    if (System.Environment.GetCommandLineArgs() is [_, var role, var path, var name]
        && role is "--p2-receive" or "--p2-witness")
    {
      var target = PenMonitor.List().Single(m => m.Name == name);
      var receiver = new ReceiverWindow(path);
      window = receiver;
      receiver.AppWindow.Move(new Windows.Graphics.PointInt32(target.Bounds.Left + 20, target.Bounds.Top + 20));
      if (role == "--p2-receive")
      {
        receiver.Activate();
      }

      try
      {
        receiver.OpenInputSurface(target, role == "--p2-receive");
      }
      catch (System.Exception error)
      {
        System.Console.Error.WriteLine(error.Message);
        receiver.Close();
      }
      return;
    }
    if (System.Environment.GetCommandLineArgs() is [_, "--p0-receive", var output])
    {
      var receiver = new ReceiverWindow(output);
      window = receiver;
      receiver.Activate();
      receiver.OpenInputSurface();
      return;
    }
    if (command is not ([_, "--diagnostics"] or [_, "--digitizer"]))
    {
      if (window is HostWindow existing)
      {
        existing.RestoreMainWindow();
        return;
      }

      var current = AppInstance.GetCurrent();
      var dispatcher = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
      current.Activated += (_, _) => dispatcher.TryEnqueue(() => (window as HostWindow)?.RestoreMainWindow());
      var primary = AppInstance.FindOrRegisterForKey("PenRelay.Main");
      if (!primary.IsCurrent)
      {
        AllowSetForegroundWindow(primary.ProcessId);
        await primary.RedirectActivationToAsync(current.GetActivatedEventArgs());
        Exit();
        return;
      }

      window = new HostWindow();
    }
    else
    {
      window = command[1] == "--diagnostics" ? new DeviceDiagnosticsWindow() : new DigitizerWindow();
    }
    window.Activate();
  }
}
