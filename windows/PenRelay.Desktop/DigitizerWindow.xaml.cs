using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;

using PenRelay.SessionFixture;

using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

public sealed class DigitizerViewModel
{
  public List<PenMonitor> Monitors { get; } = PenMonitor.List();
  public string[] Addresses { get; } = NetworkInterface.GetAllNetworkInterfaces()
      .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
      .SelectMany(n => n.GetIPProperties().UnicastAddresses)
      .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
      .Select(a => a.Address.ToString()).Append("127.0.0.1").Distinct().ToArray();

}

public sealed partial class DigitizerWindow : Window
{
  public DigitizerViewModel ViewModel { get; } = new();
  private CanvasWindow? hostWindow;
  public DigitizerWindow()
  {
    InitializeComponent();
    if (ViewModel.Monitors.Count == 1)
    {
      Target.SelectedIndex = 0;
    }

    Address.SelectedIndex = 0;
  }
  private void StartHost(object sender, RoutedEventArgs e)
  {
    if (Target.SelectedItem is not PenMonitor monitor || Address.SelectedItem is not string address)
    {
      Status.Text = "모니터와 PC 주소를 선택해 주세요.";
      return;
    }
    try
    {
      if (hostWindow == null)
      {
        hostWindow = new CanvasWindow(System.Net.IPAddress.Parse(address), monitor: monitor);
        hostWindow.Closed += (_, _) => hostWindow = null;
      }
      hostWindow.Activate();
      Status.Text = "호스트 창에서 등록과 캔버스 표시를 확인하세요.";
    }
    catch (Exception error)
    {
      Status.Text = error.Message;
    }
  }
}
