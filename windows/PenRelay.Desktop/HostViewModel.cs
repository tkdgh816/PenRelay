using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.CompilerServices;

using Microsoft.Win32;

namespace PenRelay.Desktop;

public sealed record HostAddress(string Ip, string Adapter)
{
  public override string ToString() => $"{Ip} · {Adapter}";
}
public sealed class HostViewModel : INotifyPropertyChanged
{
  public event PropertyChangedEventHandler? PropertyChanged;
  private string state = "호스트가 중지되어 있습니다.", peer = "연결된 태블릿 없음", mode = "대기",
      document = "태블릿에서 캔버스 모드를 시작하면 여기에 표시됩니다.", diagnostic = "수신한 펜 표본이 없습니다.";
  private string connection = "연결 안 됨", route = "Wi-Fi";
  public string Connection { get => connection; set => Set(ref connection, value); }
  public string Route { get => route; set => Set(ref route, value); }
  public string State { get => state; set => Set(ref state, value); }
  private string monitor = "모니터를 선택하세요.";
  public string Monitor { get => monitor; set => Set(ref monitor, value); }
  public string Peer { get => peer; set => Set(ref peer, value); }
  public string Mode { get => mode; set => Set(ref mode, value); }
  public string Document { get => document; set => Set(ref document, value); }
  public string Diagnostic { get => diagnostic; set => Set(ref diagnostic, value); }
  private void Set(ref string field, string value, [CallerMemberName] string? name = null)
  {
    if (field == value)
    {
      return;
    }

    field = value;
    PropertyChanged?.Invoke(this, new(name));
  }

  public static List<HostAddress> Addresses(ConnectionRoute route)
  {
    if (route == ConnectionRoute.Reverse)
    {
      return [new("127.0.0.1", "USB 루프백")];
    }

    var items = from nic in NetworkInterface.GetAllNetworkInterfaces()
                where nic.OperationalStatus == OperationalStatus.Up && nic.NetworkInterfaceType != NetworkInterfaceType.Loopback
                from addr in nic.GetIPProperties().UnicastAddresses
                where addr.Address.AddressFamily == AddressFamily.InterNetwork
                let usb = IsUsb(nic)
                where route != ConnectionRoute.Tether || usb
                orderby nic.GetIPProperties().GatewayAddresses.Count > 0 descending
                select new HostAddress(addr.Address.ToString(), nic.Name + " · " + nic.Description);
    return items.ToList();
  }

  private static bool IsUsb(NetworkInterface adapter)
  {
    const string networkClass = @"SYSTEM\CurrentControlSet\Control\Network\{4D36E972-E325-11CE-BFC1-08002BE10318}";
    using var connection = Registry.LocalMachine.OpenSubKey($@"{networkClass}\{adapter.Id}\Connection");
    if (connection?.GetValue("PnpInstanceID") is string device && device.StartsWith(@"USB\", StringComparison.OrdinalIgnoreCase))
    {
      return true;
    }

    return new[] { "Remote NDIS", "RNDIS", "USB", "NCM" }.Any(word =>
            adapter.Description.Contains(word, StringComparison.OrdinalIgnoreCase));
  }
}
