using System;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

internal static class WindowBounds
{
  public static void SetMinimum(Window window, FrameworkElement content, int width, int height,
    Func<double>? titleWidth = null, params FrameworkElement[] titleControls)
  {
    void Update()
    {
      if (content.XamlRoot is not { } root || window.AppWindow.Presenter is not OverlappedPresenter presenter) return;
      int pixelsWide = (int)Math.Ceiling(Math.Max(width, titleWidth?.Invoke() ?? 0) * root.RasterizationScale);
      int pixelsHigh = (int)Math.Ceiling(height * root.RasterizationScale);
      if (presenter.PreferredMinimumWidth == pixelsWide && presenter.PreferredMinimumHeight == pixelsHigh) return;
      presenter.PreferredMinimumWidth = pixelsWide;
      presenter.PreferredMinimumHeight = pixelsHigh;
      var size = window.AppWindow.Size;
      if (presenter.State == OverlappedPresenterState.Restored && (size.Width < pixelsWide || size.Height < pixelsHigh))
      {
        window.AppWindow.Resize(new Windows.Graphics.SizeInt32(Math.Max(size.Width, pixelsWide), Math.Max(size.Height, pixelsHigh)));
      }
    }
    content.Loaded += (_, _) =>
    {
      var root = content.XamlRoot;
      void Changed(XamlRoot sender, XamlRootChangedEventArgs args) => Update();
      root.Changed += Changed;
      window.Closed += (_, _) => root.Changed -= Changed;
      Update();
    };
    content.SizeChanged += (_, _) => Update();
    foreach (var control in titleControls) control.SizeChanged += (_, _) => Update();
  }
}
