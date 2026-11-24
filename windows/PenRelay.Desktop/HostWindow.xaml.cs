using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

using PenRelay.Core;
using PenRelay.SessionFixture;

using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.UI.Xaml;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

using QRCoder;

namespace PenRelay.Desktop;

public sealed partial class HostWindow : Window
{
  public HostViewModel Model { get; } = new();
  private readonly HostPreferences preferences;
  private readonly Queue<string> history = new();
  private DeviceTrust? trust;
  private DeviceTrust RegisteredDevices => trust ??= new DeviceTrust(LanHost.TrustDirectory);
  private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
  private readonly Dictionary<string, FrameworkElement> pages;
  private HostSession? session;
  private Task running = Task.CompletedTask;
  private bool editing = true, closing;
  private CanvasOverlay? overlay;
  private readonly AppTrayIcon tray;
  private bool mainDrawing = true, overlayVisible;
  private string? candidate;
  private DateTimeOffset expires;
  private string? hostFingerprint;
  private BitArray[]? directQrMatrix, inviteQrMatrix;

  private PageCatalog? lastCatalog;
  private bool peerOnline;
  private PageLibrary? navigationSession;
  private void ChooseCanvas(Guid id)
  {
    Workspace.Choose(id);
    navigationSession?.RequestSelection(id);
  }
  private void MirrorCanvas()
  {
    if (overlay == null || !CanShow(overlay))
    {
      return;
    }

    overlay.Show(Workspace.LibrarySnapshot, Workspace.CurrentId);
    Workspace.Mirror(overlay.Viewer);
  }
  private static string LibraryFolder(string pin)
  {
    try
    {
      return Path.Combine(Windows.Storage.ApplicationData.Current.LocalFolder.Path, "StrokeLibrary", pin);
    }
    catch (Exception ex) when (ex is InvalidOperationException or System.Runtime.InteropServices.COMException)
    {
      throw new IOException("보관함을 열려면 설치된 펜 릴레이 패키지를 실행하세요.", ex);
    }
  }
  private int libraryGeneration;
  private async Task ShowLibrary(string pin)
  {
    int generation = ++libraryGeneration;
    string folder;
    PageCatalog? catalog;
    try
    {
      folder = LibraryFolder(pin);
      if (lastCatalog != null && Workspace.LibrarySnapshot?.Folder == folder)
      {
        return;
      }

      lastCatalog = null;
      Workspace.SetLibrary(new(folder, null, false));
      MirrorCanvas();
      catalog = await Task.Run(() => PageLibrary.Read(folder));
    }
    catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException or
      Microsoft.Data.Sqlite.SqliteException or System.Runtime.InteropServices.COMException or System.ComponentModel.Win32Exception)
    {
      if (generation == libraryGeneration)
      {
        Error("페이지 보관함을 읽을 수 없습니다. " + ex.Message);
      }

      return;
    }
    if (generation != libraryGeneration)
    {
      return;
    }

    lastCatalog = catalog;
    Workspace.SetLibrary(new(folder, lastCatalog, false));
    if (catalog?.Selected is Guid initial)
    {
      Workspace.Choose(initial);
    }

    MirrorCanvas();
    SetSyncState(peerOnline ? "연결됨" : "오프라인");
  }
  private void SetSyncState(string state) => Model.Document = state + (lastCatalog == null ?
        " · 동기화한 페이지 없음" : $" · 마지막 동기화 {lastCatalog.SyncedAt.ToLocalTime():yyyy.MM.dd HH:mm:ss}");
  public HostWindow()
  {
    InitializeComponent();
    AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "PenRelay.ico"));
    ExtendsContentIntoTitleBar = true;
    SetTitleBar(TitleDrag);
    AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
    WindowBounds.SetMinimum(this, Shell, 720, 520);
    pages = new()
    {
      ["dashboard"] = Dashboard,
      ["digitizer"] = Digitizer,
      ["canvas"] = CanvasPage,
      ["settings"] = Settings,
      ["diagnostics"] = Diagnostics
    };
    PageStore.Children.Clear();
    preferences = HostPreferences.Load(out var warning);
    SessionPortInput.Text = preferences.SessionPort.ToString();
    PairingPortInput.Text = preferences.PairingPort.ToString();
    Route.ItemsSource = RouteOptions.Select(option => option.Label).ToArray();
    Route.SelectedIndex = Math.Max(0, Array.FindIndex(RouteOptions, option => option.Route == preferences.Route));
    ThemeChoice.SelectedIndex = Math.Clamp(preferences.Theme, 0, 2);
    CloseChoice.SelectedIndex = preferences.HideOnClose ? 0 : 1;
    MinimizeChoice.SelectedIndex = preferences.HideOnMinimize ? 1 : 0;
    MinimizeCard.Visibility = preferences.HideOnClose ? Visibility.Collapsed : Visibility.Visible;
    Rotation.SelectedIndex = Math.Clamp(preferences.QuarterTurns, 0, 3);
    Aspect.IsChecked = preferences.KeepAspect;
    ApplyTheme();
    LoadLists();
    if (preferences.MonitorId != null)
    {
      NextStep.Text = "지난번 연결 방식과 모니터를 확인하고 ‘연결 대기 시작’을 누르세요. 등록한 태블릿에서 ‘연결’과 사용할 모드를 선택하면 됩니다.";
    }

    editing = false;
    Nav.SelectedItem = Nav.MenuItems[0];
    Workspace.NavigationRequested += ChooseCanvas;
    Workspace.OverlayRequested += ToggleOverlay;
    Workspace.ViewChanged += MirrorCanvas;
    Workspace.Attach(this, synced: true);
    if (preferences.LastTablet is { Length: 64 } pin && pin.All(char.IsAsciiHexDigit))
    {
      _ = ShowLibrary(pin);
    }
    else
    {
      SetSyncState("오프라인");
    }

    if (preferences.HasSuccessfulSession)
    {
      DispatcherQueue.TryEnqueue(() => StartHost(this, new RoutedEventArgs()));
    }

    timer.Tick += (_, _) =>
    {
      if (session != null)
      {
        Model.Diagnostic = session.Telemetry.Summary();
      }

      if (expires != default && DateTimeOffset.UtcNow >= expires)
      {
        ClearInvitation();
        NextStep.Text = "초대 시간이 지났습니다. PC에서 ‘기기 초대’를 다시 누르고 새 QR 코드를 스캔하세요.";
      }
    };
    timer.Start();
    tray = new AppTrayIcon(WinRT.Interop.WindowNative.GetWindowHandle(this), RestoreMainWindow, () => DispatcherQueue.TryEnqueue(ExitApplication));
    tray.MinimizeToTray = () => !closing && !preferences.HideOnClose && preferences.HideOnMinimize && HideMainToTray();
    AppWindow.Changed += (_, _) => UpdateCanvasVisibility();
    AppWindow.Closing += (_, args) =>
    {
      if (closing && running.IsCompleted) return;
      args.Cancel = true;
      if (closing) return;
      if (!preferences.HideOnClose || !HideMainToTray())
      {
        DispatcherQueue.TryEnqueue(ExitApplication);
      }
    };
    Closed += (_, _) =>
    {
      tray.Dispose();
      trust?.Dispose();
      libraryGeneration++;
      timer.Stop();
      overlay?.Close();
      Workspace.Viewer.Detach();
    };
    if (warning != null)
    {
      Error(warning);
    }
  }
  private bool HideMainToTray()
  {
    if (!tray.EnsureRegistered()) return false;
    AppWindow.Hide();
    UpdateCanvasVisibility();
    return true;
  }

  internal void RestoreMainWindow()
  {
    if (closing) return;
    AppWindow.Show();
    if (AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } presenter)
    {
      presenter.Restore();
    }
    Activate();
    AppTrayIcon.SetForegroundWindow(WinRT.Interop.WindowNative.GetWindowHandle(this));
    UpdateCanvasVisibility();
  }

  private async void ExitApplication()
  {
    if (closing) return;
    closing = true;
    session?.Stop();
    await running;
    Close();
  }

  private static bool CanShow(Window window) => window.AppWindow.IsVisible &&
    window.AppWindow.Presenter is not OverlappedPresenter { State: OverlappedPresenterState.Minimized };

  private void UpdateCanvasVisibility()
  {
    if (closing || Workspace == null) return;
    bool main = CanShow(this) && ReferenceEquals(Pages.Content, CanvasPage);
    bool floating = overlay != null && CanShow(overlay);
    bool visible = (main && mainDrawing) || floating;
    if (session != null) session.Replica.ViewVisible = visible;
    overlay?.Viewer.SetRenderingEnabled(floating);
    Workspace.SetPresentationVisibility(main, visible);
    if (floating && !overlayVisible) MirrorCanvas();
    overlayVisible = floating;
  }

  private void Navigate(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
  {
    if (args.SelectedItem is not NavigationViewItem item || pages == null)
    {
      return;
    }

    Pages.Content = pages[(string)item.Tag];
    UpdateCanvasVisibility();
    if (item.Tag as string == "settings")
    {
      ShowAdbLocation();
      ListDevices();
    }
  }
  private void GoDashboard(object sender, RoutedEventArgs e) => Nav.SelectedItem = Nav.MenuItems[0];
  private async void Recover(object sender, RoutedEventArgs e)
  {
    session?.Stop();
    await running;
    Nav.SelectedItem = Nav.MenuItems[0];
    LoadLists();
    Issue.IsOpen = false;
    Route.Focus(FocusState.Programmatic);
  }
  private async void RetryHost(object sender, RoutedEventArgs e)
  {
    session?.Stop();
    await running;
    StartHost(sender, e);
  }
  private string? listeningAddress;
  private static readonly (ConnectionRoute Route, string Label)[] RouteOptions =
  [
    (ConnectionRoute.Wifi, "Wi-Fi (같은 공유기)"),
    (ConnectionRoute.Direct, "Wi-Fi Direct (PC 직접 연결)"),
    (ConnectionRoute.Reverse, "USB ADB (USB 디버깅)"),
    (ConnectionRoute.Tether, "USB 테더링 (USB 네트워크)")
  ];
  private ConnectionRoute SelectedRoute => Route.SelectedIndex < 0 ? ConnectionRoute.Wifi : RouteOptions[Route.SelectedIndex].Route;
  private void LoadLists()
  {
    bool before = editing;
    editing = true;
    try
    {
      var monitors = PenMonitor.List();
      Target.ItemsSource = monitors;
      var previous = monitors.FirstOrDefault(m => m.Identity == preferences.MonitorId && m.Name == preferences.MonitorName);
      Target.SelectedItem = previous ?? (preferences.MonitorId == null ? monitors.FirstOrDefault() : null);
      var addresses = SelectedRoute == ConnectionRoute.Direct ? new List<HostAddress>() : HostViewModel.Addresses(SelectedRoute);
      Address.Visibility = SelectedRoute == ConnectionRoute.Direct ? Visibility.Collapsed : Visibility.Visible;
      Address.ItemsSource = addresses;
      var saved = SelectedRoute == ConnectionRoute.Tether ? preferences.TetherAddress : preferences.WifiAddress;
      Address.SelectedItem = addresses.FirstOrDefault(a => a.Ip == saved) ?? addresses.FirstOrDefault();
      Model.Route = "연결 방식 · " + Route.SelectedItem;
      RouteHelp.Text = SelectedRoute switch
      {
        ConnectionRoute.Direct => "대기를 시작한 뒤 태블릿에서 PC 연결 QR을 스캔하거나 연결 코드를 직접 입력하세요.",
        ConnectionRoute.Reverse => "USB 디버깅을 승인한 태블릿 한 대를 연결하세요. 앱이 ADB 포트 두 개를 관리합니다.",
        ConnectionRoute.Tether => "태블릿에서 USB 테더링을 켜고 USB 어댑터의 IPv4 주소를 선택하세요.",
        _ => "같은 네트워크의 태블릿에서 접속할 수 있는 PC 주소를 선택하세요."
      };
      Model.Monitor = ScreenName.Text = Target.SelectedItem?.ToString() ?? "대상 모니터를 선택하세요.";
    }
    catch (Exception ex)
    {
      Error(ex.Message);
    }
    finally
    {
      editing = before;
    }
  }
  private void RefreshLists(object sender, RoutedEventArgs e) => LoadLists();
  private void RouteChanged(object sender, SelectionChangedEventArgs e)
  {
    if (editing)
    {
      return;
    }

    LoadLists();
  }
  private void SelectionEdited(object sender, SelectionChangedEventArgs e)
  {
    if (editing)
    {
      return;
    }

    Model.Monitor = ScreenName.Text = Target.SelectedItem?.ToString() ?? "대상 모니터를 선택하세요.";
  }
  private void SaveSelection()
  {
    preferences.Route = SelectedRoute;
    preferences.HasSuccessfulSession = true;
    if (Target.SelectedItem is PenMonitor target)
    {
      preferences.MonitorId = target.Identity;
      preferences.MonitorName = target.Name;
      ScreenName.Text = target.ToString();
    }
    if (Address.SelectedItem is HostAddress a)
    {
      if (SelectedRoute == ConnectionRoute.Wifi)
      {
        preferences.WifiAddress = a.Ip;
      }

      if (SelectedRoute == ConnectionRoute.Tether)
      {
        preferences.TetherAddress = a.Ip;
      }
    }
    Save();
  }
  private void ThemeChanged(object sender, SelectionChangedEventArgs e)
  {
    if (editing)
    {
      return;
    }

    preferences.Theme = ThemeChoice.SelectedIndex;
    ApplyTheme();
    Save();
  }
  private void CloseChoiceChanged(object sender, SelectionChangedEventArgs e)
  {
    if (editing) return;
    preferences.HideOnClose = CloseChoice.SelectedIndex == 0;
    MinimizeCard.Visibility = preferences.HideOnClose ? Visibility.Collapsed : Visibility.Visible;
    Save();
  }
  private void MinimizeChoiceChanged(object sender, SelectionChangedEventArgs e)
  {
    if (editing) return;
    preferences.HideOnMinimize = MinimizeChoice.SelectedIndex == 1;
    Save();
  }
  private void ApplyTheme()
  {
    Shell.RequestedTheme = (ElementTheme)ThemeChoice.SelectedIndex;
    if (overlay?.Content is FrameworkElement root)
    {
      root.RequestedTheme = Shell.RequestedTheme;
    }
  }
  private void Save()
  {
    try
    {
      preferences.Save();
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
    {
      Error("설정 저장 실패 · " + ex.Message);
    }
  }
  private void MappingChanged(object sender, SelectionChangedEventArgs e) => SaveMapping();
  private void MappingEdited(object sender, RoutedEventArgs e) => SaveMapping();
  private void SaveMapping()
  {
    if (editing)
    {
      return;
    }

    preferences.KeepAspect = Aspect.IsChecked == true;
    preferences.QuarterTurns = Rotation.SelectedIndex;
    Save();
  }
  private void ShowAdbLocation()
  {
    var selected = preferences.AdbPath;
    try
    {
      var path = UsbPortBridge.ResolveAdb(selected);
      AdbLocation.Text = (string.IsNullOrWhiteSpace(selected) ? "자동 검색 · " : "선택한 파일 · ") + path;
    }
    catch (FileNotFoundException)
    {
      AdbLocation.Text = string.IsNullOrWhiteSpace(selected)
        ? "adb.exe를 찾을 수 없습니다. 파일을 선택하세요."
        : $"선택한 파일을 찾을 수 없습니다 · {selected}";
    }
  }
  private async void ChooseAdb(object sender, RoutedEventArgs e)
  {
    try
    {
      var picker = new Windows.Storage.Pickers.FileOpenPicker();
      WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
      picker.FileTypeFilter.Add(".exe");
      var file = await picker.PickSingleFileAsync();
      if (file == null)
      {
        return;
      }

      if (!file.Name.Equals("adb.exe", StringComparison.OrdinalIgnoreCase))
      {
        Error("Android Platform Tools의 adb.exe를 선택하세요.");
        return;
      }
      preferences.AdbPath = file.Path;
      ShowAdbLocation();
      Save();
    }
    catch (Exception ex)
    {
      Error(ex.Message);
    }
  }
  private void ShowStartError(string message)
  {
    Model.State = "연결 실패";
    ConnectionIssue.Message = message;
    ConnectionIssue.IsOpen = true;
    ConnectionIssue.StartBringIntoView();
  }
  private void StartHost(object sender, RoutedEventArgs e)
  {
    if (session != null)
    {
      return;
    }

    var address = Address.SelectedItem as HostAddress;
    var monitor = Target.SelectedItem as PenMonitor;
    var issues = new List<string>();
    Control? first = null;
    void Invalid(Control field, string message)
    {
      first ??= field;
      issues.Add(message);
    }
    if (Route.SelectedIndex < 0) Invalid(Route, "연결 방식을 선택해 주세요.");
    if (SelectedRoute != ConnectionRoute.Direct && address == null)
      Invalid(Address, "PC 대기 주소를 선택해 주세요. 주소가 없으면 네트워크 연결을 확인하고 목록을 새로 고치세요.");
    if (!ushort.TryParse(SessionPortInput.Text, out var sessionPort) || sessionPort == 0)
      Invalid(SessionPortInput, "세션 포트에 1부터 65535 사이의 숫자를 입력해 주세요.");
    if (!ushort.TryParse(PairingPortInput.Text, out var pairingPort) || pairingPort == 0)
      Invalid(PairingPortInput, "등록 포트에 1부터 65535 사이의 숫자를 입력해 주세요.");
    else if (sessionPort == pairingPort)
      Invalid(PairingPortInput, "등록 포트는 세션 포트와 다른 번호를 입력해 주세요.");
    if (monitor == null)
      Invalid(Target, "펜 입력을 받을 모니터를 선택해 주세요.");
    else if (!monitor.Current())
      Invalid(Target, "선택한 모니터를 찾을 수 없습니다. 목록을 새로 고친 뒤 다시 선택해 주세요.");
    if (issues.Count > 0)
    {
      first?.Focus(FocusState.Programmatic);
      ShowStartError(string.Join("\n", issues));
      return;
    }
    DeviceTrust registered;
    try { registered = RegisteredDevices; }
    catch (Exception ex)
    {
      ShowStartError("등록된 기기 목록을 열 수 없습니다. " + ex.Message);
      return;
    }
    ConnectionIssue.IsOpen = false;
    preferences.SessionPort = sessionPort;
    preferences.PairingPort = pairingPort;
    Save();
    Issue.IsOpen = false;
    var route = SelectedRoute;
    var adbPath = preferences.AdbPath;
    HostSession? owner = null;
    void Post(Action change) => DispatcherQueue.TryEnqueue(() =>
    {
      if (session == owner)
      {
        change();
      }
    });
    var monitorKey = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(monitor!.Identity)));
    var mapping = new PenMappingOptions(monitorKey, preferences.KeepAspect, preferences.QuarterTurns, Calibrate.IsChecked == true, monitor.Name);
    owner = new(mode => Post(() =>
    {
      Model.Mode = "활성 모드 · " + (mode switch { "Canvas" => "캔버스", "Digitizer" => "디지타이저", _ => mode });
      Workspace.SetCanvasActive(mode == "Canvas");
      if (mode == "Digitizer")
      {
        Calibrate.IsChecked = false;
      }

      if (mode == "Canvas")
      {
        Nav.SelectedItem = CanvasItem;
      }
      else if (mode == "Digitizer")
      {
        Nav.SelectedItem = DigitizerItem;
      }
    }), (pin, name, supportsPages) => Post(() =>
    {
      peerOnline = true;
      Model.Connection = "연결됨";
      Model.Peer = name;
      Model.State = $"연결됨 · {name} · {Route.SelectedItem}";
      Issue.IsOpen = false;
      preferences.LastTablet = pin;
      _ = ShowLibrary(pin);
      Workspace.SetLiveOnly(!supportsPages);
      SaveSelection();
    }), mapping, pin =>
    {
      var folder = LibraryFolder(pin);
      var library = new PageLibrary(folder, update => Post(() =>
      {
        libraryGeneration++;
        lastCatalog = update.Catalog;
        Workspace.SetLibrary(update);
        if (update.Selection is Guid id)
        {
          Workspace.Choose(id);
        }

        MirrorCanvas();
        SetSyncState(update.Transferring ? "동기화 중" : "동기화 완료");
      }));
      Post(() => navigationSession = library);
      return library;
    }, sessionPort, pairingPort, registered);
    session = owner;
    UpdateCanvasVisibility();
    Model.State = $"연결 대기 중 · {Route.SelectedItem}";
    Workspace.Bind(owner.Replica);
    overlay?.Viewer.Bind(owner.Replica);
    SessionPortInput.IsEnabled = PairingPortInput.IsEnabled = Start.IsEnabled = Route.IsEnabled = Target.IsEnabled = Address.IsEnabled = Refresh.IsEnabled = false;
    Stop.IsEnabled = true;
    Rotation.IsEnabled = Aspect.IsEnabled = Calibrate.IsEnabled = false;
    running = Run();
    async Task Run()
    {
      try
      {
        if (route == ConnectionRoute.Direct)
        {
          using var direct = new WifiDirectHost();
          var code = direct.ConfigureCode();
          DirectCode.Text = "수동 입력 코드 · " + code;
          SetQr(DirectQr, "PENRELAY-DIRECT-1:" + code);
          DirectJoinPanel.Visibility = Visibility.Visible;
          await direct.Run(owner.Token, ConfirmPeer, Report, async (ip, token) =>
          {
            listeningAddress = ip.ToString();
            try
            {
              await Task.Run(() => owner.Serve(ip, monitor, message => Post(() => Report(message)), token));
            }
            finally
            {
              listeningAddress = null;
              ClearInvitation();
              Invite.IsEnabled = false;
              hostFingerprint = null;
              Ready.Text = "";
            }
          });
        }
        else
        {
          listeningAddress = address!.Ip;
          await Task.Run(() => owner.Run(route, IPAddress.Parse(address.Ip), monitor, adbPath,
                  message => Post(() => Report(message))));
        }
      }
      catch (OperationCanceledException) when (owner.Token.IsCancellationRequested) { }
      catch (Exception ex)
      {
        ShowStartError(ex.Message);
        Error(ex.Message);
      }
      finally
      {
        DirectCode.Text = "";
        SetQr(DirectQr, null);
        DirectJoinPanel.Visibility = Visibility.Collapsed;
        listeningAddress = null;
        session = null;
        Model.Diagnostic = owner.Telemetry.Summary();
        overlay?.Viewer.Bind(null);
        SessionPortInput.IsEnabled = PairingPortInput.IsEnabled = Start.IsEnabled = Route.IsEnabled = Target.IsEnabled = Address.IsEnabled = Refresh.IsEnabled = true;
        Stop.IsEnabled = Invite.IsEnabled = false;
        Rotation.IsEnabled = Aspect.IsEnabled = Calibrate.IsEnabled = true;
        if (Model.State != "연결 실패")
        {
          Model.State = "연결 끊김";
        }

        peerOnline = false;
        navigationSession = null;
        Model.Connection = "연결 안 됨";
        Workspace.SetCanvasActive(false);
        SetSyncState("오프라인");
        Model.Peer = "연결된 태블릿 없음";
        Model.Mode = "대기";
        Ready.Text = "";
        hostFingerprint = null;
        NextStep.Text = Model.State == "연결 실패" ? Issue.Message : "PC 주소와 모니터를 확인한 뒤 ‘연결 대기 시작’을 누르세요.";
        ListDevices();
        ClearInvitation();
      }
    }
  }
  private async Task<bool> ConfirmPeer(string name, System.Threading.CancellationToken token)
  {
    var dialog = new ContentDialog
    {
      XamlRoot = Nav.XamlRoot, RequestedTheme = Shell.RequestedTheme,
      Title = "Wi-Fi Direct 연결 요청", Content = name + " 기기의 연결을 허용할까요?",
      PrimaryButtonText = "연결 허용", CloseButtonText = "거부", DefaultButton = ContentDialogButton.Close
    };
    using var cancel = token.Register(() => DispatcherQueue.TryEnqueue(() => dialog.Hide()));
    return await dialog.ShowAsync() == ContentDialogResult.Primary && !token.IsCancellationRequested;
  }
  private void Report(string message)
  {
    if (message.StartsWith("명령:"))
    {
      return;
    }

    if (message.StartsWith("무선 연결 실패"))
    {
      Error(message);
    }
    else if (message.StartsWith("Wi-Fi Direct"))
    {
      NextStep.Text = message;
      Model.State = "무선 연결 대기 중";
    }
    else if (message.StartsWith("준비 "))
    {
      var parts = message.Split(' ');
      hostFingerprint = parts[3];
      Ready.Text = $"PC {Environment.MachineName} · {listeningAddress}\n세션 {parts[1]} · 등록 {parts[2]}\nPC 지문 {hostFingerprint}";
      NextStep.Text = "처음이라면 ‘기기 초대’를 누르세요. 이미 등록한 태블릿에서는 ‘연결’을 누르세요.";
      Model.State = "연결 대기 중";
      Invite.IsEnabled = true;
      ListDevices();
    }
    else if (message.StartsWith("초대 "))
    {
      ClearInvitation();
      expires = DateTimeOffset.UtcNow.AddMinutes(2);
      NextStep.Text = "태블릿에서 ‘PC 초대 QR 스캔’을 누르고 이 코드를 비추세요. 카메라를 사용할 수 없으면 ‘연결 방식 변경’에서 아래 정보를 입력하세요.";
      ShowQr(message.Substring(3));
      Pairing.Text = "일회용 비밀 · " + message.Substring(3);
      return;
    }
    else if (message.StartsWith("승인대기 "))
    {
      candidate = message.Substring(5);
      InviteQr.Visibility = Visibility.Collapsed;
      SetQr(InviteQr, null);
      NextStep.Text = "태블릿 화면에 표시된 기기 지문이 아래와 같으면 ‘지문 확인 후 승인’을 누르세요.";
      Pairing.Text = "태블릿의 기기 지문과 대조하세요.\n" + candidate;
      Approve.IsEnabled = true;
      return;
    }
    else if (message.StartsWith("등록 ") || message == "폐기 완료")
    {
      ClearInvitation();
      Pairing.Text = message;
      ListDevices();
      NextStep.Text = message == "등록 완료" ? "태블릿이 자동으로 연결됩니다. 태블릿에서 사용할 작업을 선택하세요." : "태블릿을 등록하려면 PC에서 ‘기기 초대’를 다시 누르세요.";
    }
    else if (message == "인증 중")
    {
      Model.State = $"연결 중 · {Route.SelectedItem}";
    }
    else if (message.StartsWith("연결 정리") || message.StartsWith("USB 재연결 대기"))
    {
      if (!peerOnline && Model.State != "연결 끊김")
      {
        Model.State = "연결 실패";
      }

      Error(message);
    }
    else if (message.StartsWith("연결 "))
    {
      Model.State = "연결됨";
      NextStep.Text = "태블릿에서 ‘캔버스’ 또는 ‘디지타이저’를 선택하세요.";
      Issue.IsOpen = false;
    }
    else if (message.StartsWith("종료 "))
    {
      peerOnline = false;
      navigationSession = null;
      Model.Connection = "연결 안 됨";
      Workspace.SetCanvasActive(false);
      SetSyncState("오프라인");
      Model.State = "연결 끊김";
      Model.Peer = "연결된 태블릿 없음";
      Model.Mode = "대기";
    }
    if (message.StartsWith("준비 "))
    {
      message = "호스트 준비";
    }

    Log(message);
  }
  private void Log(string message)
  {
    if (history.Count == 16)
    {
      history.Dequeue();
    }

    history.Enqueue($"{DateTime.Now:HH:mm:ss}  {message}");
    History.Text = string.Join("\n", history.Reverse());
  }
  private void Error(string message)
  {
    Issue.Message = Route.SelectedItem + " · " + message + "\nPC에서 연결 방식·주소·모니터를 확인하고 태블릿에서 같은 연결 방식을 선택하세요.";
    Issue.IsOpen = true;
  }
  private void StopHost(object sender, RoutedEventArgs e)
  {
    Stop.IsEnabled = false;
    session?.Stop();
  }
  private void ClearInvitation()
  {
    expires = default;
    candidate = null;
    Pairing.Text = "";
    Approve.IsEnabled = false;
    SetQr(InviteQr, null);
    InviteQr.Visibility = Visibility.Collapsed;
  }
  private void ShowQr(string token)
  {
    var payload = JsonSerializer.Serialize(new
    {
      type = "penrelay-invite-1",
      pc = Environment.MachineName,
      route = SelectedRoute.ToString(),
      host = listeningAddress,
      port = session!.SessionPort,
      pairingPort = session.PairingPort,
      pin = hostFingerprint,
      token
    });
    SetQr(InviteQr, payload);
    InviteQr.Visibility = Visibility.Visible;
    InviteQr.StartBringIntoView();
  }
  private void SetQr(CanvasControl view, string? payload)
  {
    using var data = payload == null ? null : QRCodeGenerator.GenerateQrCode(payload, QRCodeGenerator.ECCLevel.M);
    if (view == DirectQr)
    {
      directQrMatrix = data?.ModuleMatrix.ToArray();
    }
    else
    {
      inviteQrMatrix = data?.ModuleMatrix.ToArray();
    }
    view.Invalidate();
  }
  private void DrawQr(CanvasControl view, CanvasDrawEventArgs args)
  {
    var matrix = view == DirectQr ? directQrMatrix : inviteQrMatrix;
    if (matrix == null)
    {
      return;
    }
    int width = (int)Math.Round(view.ActualWidth * view.Dpi / 96);
    int height = (int)Math.Round(view.ActualHeight * view.Dpi / 96);
    int step = Math.Min(width, height) / matrix.Length;
    int left = (width - matrix.Length * step) / 2;
    int top = (height - matrix.Length * step) / 2;
    var drawing = args.DrawingSession;
    drawing.Units = CanvasUnits.Pixels;
    drawing.Antialiasing = CanvasAntialiasing.Aliased;
    for (int y = 0; y < matrix.Length; y++)
    {
      for (int x = 0; x < matrix.Length; x++)
      {
        if (matrix[y][x])
        {
          drawing.FillRectangle(left + x * step, top + y * step, step, step, Microsoft.UI.Colors.Black);
        }
      }
    }
  }
  private void InviteDevice(object sender, RoutedEventArgs e) => session?.Command("invite");
  private void ApproveDevice(object sender, RoutedEventArgs e)
  {
    if (candidate != null)
    {
      session?.Command("approve " + candidate);
    }

    ClearInvitation();
  }
  private void ListDevices()
  {
    DeviceError.Visibility = Visibility.Collapsed;
    var previous = Devices.SelectedItem as string;
    try
    {
      var pins = RegisteredDevices.RegisteredKeys();
      Devices.ItemsSource = pins;
      Devices.SelectedItem = pins.FirstOrDefault(pin => pin == previous) ?? pins.FirstOrDefault();
      Devices.PlaceholderText = "등록된 기기 없음";
    }
    catch (Exception ex)
    {
      Devices.ItemsSource = null;
      Devices.PlaceholderText = "등록 목록을 열 수 없습니다";
      ShowDeviceError(ex.Message);
    }
  }
  private void DeviceSelected(object sender, SelectionChangedEventArgs e)
  {
    if (Revoke == null) return;
    DeviceError.Visibility = Visibility.Collapsed;
    Revoke.IsEnabled = Devices.SelectedItem is string;
  }
  private void ShowDeviceError(string message)
  {
    DeviceError.Text = message;
    DeviceError.Visibility = Visibility.Visible;
  }
  private async void RevokeDevice(object sender, RoutedEventArgs e)
  {
    if (Devices.SelectedItem is not string key)
    {
      return;
    }

    var dialog = new ContentDialog
    {
      XamlRoot = Nav.XamlRoot,
      RequestedTheme = Shell.RequestedTheme,
      Title = "이 기기의 등록을 해제하시겠습니까?",
      Content = key,
      PrimaryButtonText = "등록 해제",
      CloseButtonText = "취소",
      DefaultButton = ContentDialogButton.Close
    };
    if (await dialog.ShowAsync() == ContentDialogResult.Primary)
    {
      try
      {
        RegisteredDevices.Revoke(key);
        ListDevices();
      }
      catch (Exception ex) { ShowDeviceError("등록 해제 실패 · " + ex.Message); }
    }
  }
  private void ToggleOverlay(object sender, RoutedEventArgs e)
  {
    if (overlay != null)
    {
      overlay.Close();
      return;
    }
    overlay = new(session?.Replica, Workspace);
    overlay.NavigationRequested += ChooseCanvas;
    overlay.AppWindow.Changed += (_, _) => UpdateCanvasVisibility();
    overlay.DrawingVisibilityChanged += visible =>
    {
      mainDrawing = visible;
      Workspace.ShowDrawing(visible);
      UpdateCanvasVisibility();
    };
    MirrorCanvas();
    ((FrameworkElement)overlay.Content).RequestedTheme = Shell.RequestedTheme;
    overlay.Closed += (_, _) =>
    {
      overlay = null;
      mainDrawing = true;
      Workspace.ShowDrawing(true);
      Workspace.SetOverlayOpen(false);
      UpdateCanvasVisibility();
    };
    overlay.Activate();
    UpdateCanvasVisibility();
    Workspace.SetOverlayOpen(true);
  }
  private void ConnectionsResized(object sender, SizeChangedEventArgs e)
  {
    bool wide = e.NewSize.Width >= 700;
    InviteColumn.Width = wide ? new GridLength(.82, GridUnitType.Star) : new GridLength(0);
    Grid.SetColumn(InvitationPanel, wide ? 1 : 0);
    Grid.SetRow(InvitationPanel, wide ? 0 : 1);
  }
}
