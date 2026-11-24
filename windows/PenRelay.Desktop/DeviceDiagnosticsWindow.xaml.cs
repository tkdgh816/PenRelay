using Microsoft.Graphics.Canvas.UI.Xaml;
using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

public sealed partial class DeviceDiagnosticsWindow : Window
{
  public DeviceDiagnosticsViewModel ViewModel { get; } = new(PenDeviceDiagnostics.CheckCreation());

  public DeviceDiagnosticsWindow() => InitializeComponent();

  private void DrawSample(CanvasControl sender, CanvasDrawEventArgs args)
  {
    args.DrawingSession.DrawLine(8, 8, 112, 112, Windows.UI.Color.FromArgb(255, 49, 131, 224), 4);
  }
}

public sealed record DeviceDiagnosticsViewModel(string DeviceStatus);
