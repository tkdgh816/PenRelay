using System;
using System.Net;
using System.Threading.Channels;
using System.Threading.Tasks;

using PenRelay.Core;
using PenRelay.SessionFixture;

using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

public sealed class CanvasViewModel
{
  public CanvasReplica Replica { get; } = new();
  private readonly Channel<string> commands = Channel.CreateUnbounded<string>();
  public void Command(string command) => commands.Writer.TryWrite(command);
  public void Close() => commands.Writer.TryComplete();
  private async Task<string?> Read() =>
      await commands.Reader.WaitToReadAsync() ? await commands.Reader.ReadAsync() : null;

  public Task Run(IPAddress ip, int port, int pairPort, PenMonitor? monitor, bool fixture, Action<string> status, bool benchmark) =>
      LanHost.Run(ip, port, pairPort, fixture, monitor, Replica, fixture ? Console.In.ReadLineAsync : Read, status, slowCanvas: !benchmark);
}

public sealed partial class CanvasWindow : Window
{
  public CanvasViewModel ViewModel { get; } = new();
  private readonly bool fixture;
  private string? candidate;
  private string host = "";
  public CanvasWindow(IPAddress address, int port = 47120, int pairPort = 47121, PenMonitor? monitor = null, bool fixture = false, bool benchmark = false)
  {
    InitializeComponent();
    this.fixture = fixture;

    DocumentStatus.Text = "태블릿의 캔버스 모드를 기다리는 중";
    Workspace.Attach(this, fixture ? Environment.GetEnvironmentVariable("PENRELAY_FIXTURE_PAGES") : null);
    Workspace.Bind(ViewModel.Replica, fixture, benchmark);
    Workspace.Viewer.Presented += image => DocumentStatus.Text = $"확정 r{image.Revision} · {image.Width} × {image.Height} · {image.Hash}";
    Closed += (_, _) =>
    {
      Workspace.Viewer.Detach();
      ViewModel.Close();
    };
    _ = Task.Run(async () =>
    {
      try
      {
        await ViewModel.Run(address, port, pairPort, monitor, fixture, Report, benchmark);
      }
      catch (Exception ex)
      {
        Report("호스트 시작 또는 연결 실패 · " + ex.Message);
      }
      finally
      {
        if (fixture)
        {
          DispatcherQueue.TryEnqueue(Close);
        }
      }
    });
  }
  private void Report(string message)
  {
    if (fixture)
    {
      Console.WriteLine(message);
    }

    DispatcherQueue.TryEnqueue(() =>
    {
      if (message.StartsWith("명령:"))
      {
        return;
      }

      if (message.StartsWith("준비 "))
      {
        host = message;
      }

      if (message.StartsWith("승인대기 "))
      {
        candidate = message.Substring(5);
      }

      if (message.StartsWith("등록 ") || message.StartsWith("초대 "))
      {
        candidate = null;
      }

      ApproveButton.IsEnabled = candidate != null;
      HostStatus.Text = message == host ? host : host + "\n" + message;
    });
  }
  private void Invite(object sender, RoutedEventArgs e) => ViewModel.Command("invite");
  private void Approve(object sender, RoutedEventArgs e)
  {
    if (candidate != null)
    {
      ViewModel.Command("approve " + candidate);
    }

    ApproveButton.IsEnabled = false;
  }
  private void Disconnect(object sender, RoutedEventArgs e) => ViewModel.Command("disconnect");
}
