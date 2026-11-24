using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

using PenRelay.Core;

using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

using Windows.Foundation;

namespace PenRelay.Desktop;

internal sealed class CanvasOverlay : Window
{
  private const int StyleIndex = -20, Layered = 0x80000, Transparent = 0x20;
  private readonly CanvasViewer viewer = new();
  private readonly nint hwnd;
  private readonly int original;
  private readonly CanvasNavigator navigator = new(iconButtons: true);
  public CanvasViewer Viewer => viewer;
  public event Action<Guid>? NavigationRequested;
  public event Action<bool>? DrawingVisibilityChanged;
  private readonly Grid root = new();
  private readonly DispatcherTimer hitTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };
  private bool passing;
  public void Show(LibraryUpdate? update, Guid? id) => navigator.Show(update, id);
  public CanvasOverlay(CanvasReplica? replica, CanvasWorkspace workspace)
  {
    Title = "캔버스 오버레이";
    AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "PenRelay.ico"));
    SystemBackdrop = new MicaBackdrop();
    ExtendsContentIntoTitleBar = true;
    var tools = new CommandBar
    {
      DefaultLabelPosition = CommandBarDefaultLabelPosition.Collapsed,
      OverflowButtonVisibility = CommandBarOverflowButtonVisibility.Collapsed,
      IsDynamicOverflowEnabled = false,
      VerticalAlignment = VerticalAlignment.Center,
      Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent)
    };
    var top = new AppBarToggleButton { Label = "항상 위", Icon = new SymbolIcon(Symbol.Pin) };
    var more = new AppBarButton { Label = "오버레이 옵션", Icon = new SymbolIcon(Symbol.More) };
    top.Width = more.Width = 48;
    top.MinWidth = more.MinWidth = 0;
    top.Padding = more.Padding = new Thickness(0);
    AutomationProperties.SetName(top, "항상 위");
    AutomationProperties.SetName(more, "오버레이 옵션");
    ToolTipService.SetToolTip(top, "항상 위");
    ToolTipService.SetToolTip(more, "오버레이 옵션");
    var menu = new MenuFlyout();
    var hide = new ToggleMenuFlyoutItem { Text = "본창에서 그림 숨기기" };
    var passthrough = new ToggleMenuFlyoutItem { Text = "그림 영역 클릭 통과" };
    workspace.AddPageActions(menu, this);
    menu.Items.Add(new MenuFlyoutSeparator());
    menu.Items.Add(hide);
    menu.Items.Add(passthrough);
    more.Click += (_, _) => menu.ShowAt(more);
    tools.PrimaryCommands.Add(top);
    tools.PrimaryCommands.Add(more);
    var caption = new TextBlock
    {
      Text = Title,
      TextTrimming = TextTrimming.CharacterEllipsis,
      HorizontalAlignment = HorizontalAlignment.Center,
      VerticalAlignment = VerticalAlignment.Center,
      IsHitTestVisible = false
    };
    var bar = new TitleBar
    {
      Height = 48,
      LeftHeader = navigator.Commands,
      Content = caption,
      RightHeader = tools
    };
    root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
    root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
    root.RowDefinitions.Add(new RowDefinition());
    root.Children.Add(bar);
    Grid.SetRow(navigator.Filmstrip, 1);
    Grid.SetRow(viewer, 2);
    root.Children.Add(navigator.Filmstrip);
    root.Children.Add(viewer);
    Content = root;
    SetTitleBar(bar);
    AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
    WindowBounds.SetMinimum(this, root, 640, 360, () =>
    {
      double scale = root.XamlRoot?.RasterizationScale ?? 1;
      double left = navigator.Commands.ActualWidth + AppWindow.TitleBar.LeftInset / scale;
      double right = tools.ActualWidth + AppWindow.TitleBar.RightInset / scale;
      return 2 * Math.Max(left, right) + caption.ActualWidth + 32;
    }, navigator.Commands, tools, caption);
    hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
    original = GetWindowLongW(hwnd, StyleIndex);
    viewer.Bind(replica);
    navigator.Requested += id => NavigationRequested?.Invoke(id);
    top.Click += (_, _) => ((OverlappedPresenter)AppWindow.Presenter).IsAlwaysOnTop = top.IsChecked == true;
    hide.Click += (_, _) => DrawingVisibilityChanged?.Invoke(!hide.IsChecked);
    passthrough.Click += (_, _) =>
    {
      if (passthrough.IsChecked)
      {
        hitTimer.Start();
      }
      else
      {
        hitTimer.Stop();
        PassClicks(false);
      }
    };
    hitTimer.Tick += (_, _) =>
    {
      if (root.XamlRoot == null || !GetCursorPos(out var point) || !ScreenToClient(hwnd, ref point))
      {
        return;
      }

      var scale = root.XamlRoot.RasterizationScale;
      var area = viewer.TransformToVisual(root).TransformBounds(new Rect(0, 0, viewer.ActualWidth, viewer.ActualHeight));
      PassClicks(!menu.IsOpen && area.Contains(new Point(point.X / scale, point.Y / scale)));
    };
    AppWindow.Resize(new Windows.Graphics.SizeInt32(720, 480));
    Closed += (_, _) =>
    {
      hitTimer.Stop();
      viewer.Detach();
    };
  }
  [StructLayout(LayoutKind.Sequential)] private struct CursorPoint { public int X, Y; }
  [DllImport("user32")] private static extern bool GetCursorPos(out CursorPoint point);
  [DllImport("user32")] private static extern bool ScreenToClient(nint hwnd, ref CursorPoint point);
  private void PassClicks(bool enabled)
  {
    if (passing == enabled)
    {
      return;
    }

    int style = GetWindowLongW(hwnd, StyleIndex) & ~(Layered | Transparent);
    style |= enabled ? Layered | Transparent : original & (Layered | Transparent);
    Marshal.SetLastPInvokeError(0);
    if (SetWindowLongW(hwnd, StyleIndex, style) == 0 && Marshal.GetLastPInvokeError() != 0)
    {
      throw new Win32Exception();
    }

    if (enabled && !SetLayeredWindowAttributes(hwnd, 0, 255, 2))
    {
      SetWindowLongW(hwnd, StyleIndex, original);
      throw new Win32Exception();
    }
    passing = enabled;
  }
  [DllImport("user32")] private static extern int GetWindowLongW(nint hwnd, int index);
  [DllImport("user32", SetLastError = true)] private static extern int SetWindowLongW(nint hwnd, int index, int value);
  [DllImport("user32", SetLastError = true)] private static extern bool SetLayeredWindowAttributes(nint hwnd, uint color, byte alpha, uint flags);
}
