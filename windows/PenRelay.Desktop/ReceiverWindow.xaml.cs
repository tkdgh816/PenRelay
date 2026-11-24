using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

using PenRelay.SessionFixture;

using Microsoft.UI.Xaml;

namespace PenRelay.Desktop;

public sealed partial class ReceiverWindow : Window
{
  private readonly StreamWriter log;
  private readonly string output;
  private readonly SubclassProc callback;
  private nint surface;
  private nint host;

  public ReceiverWindow(string path)
  {
    InitializeComponent();
    output = path;
    log = new StreamWriter(new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.Read)) { AutoFlush = true };
    log.WriteLine("message\tid\tframe\tx\ty\trawX\trawY\tpressure\tmask\ttiltX\ttiltY\tpenFlags\tflags\ttype");
    callback = HandleInput;
    Closed += (_, _) =>
    {
      if (surface != 0)
      {
        RemoveWindowSubclass(surface, callback, 1);
      }

      log.Dispose();
    };
  }

  internal void OpenInputSurface(PenMonitor? target = null, bool foreground = false)
  {
    host = WinRT.Interop.WindowNative.GetWindowHandle(this);
    var origin = target == null ? SurfaceOrigin() : new Pixel(target.Bounds.Left, target.Bounds.Top);
    // An owned popup has its own input target outside XAML's composition input site.
    surface = CreateWindowExW(foreground ? 0x80u : 0x08000080u, "STATIC", "P0-W input", 0x90800106, origin.X, origin.Y,
        target?.Bounds.Width ?? 360, target?.Bounds.Height ?? 200, host, 0, 0, 0);
    if (surface == 0 || !SetWindowSubclass(surface, callback, 1, 0))
    {
      throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    if (target != null)
    {
      if (!SetWindowPos(surface, -1, origin.X, origin.Y, target.Bounds.Width, target.Bounds.Height, 0x10))
      {
        throw new Win32Exception(Marshal.GetLastWin32Error());
      }

      if (foreground && GetForegroundWindow() != surface)
      {
        SetForegroundWindow(surface);
      }
      // 전경 활성화는 Windows 정책에 따라 비동기로 적용되거나 거부된다.
      // 합성 펜이 향할 실제 화면 좌표의 최상위 입력 창을 확인한 뒤에만 재생한다.
      if (foreground)
      {
        var bounds = target.Bounds;
        foreach (var x in new[] { 1, bounds.Width / 2, bounds.Width - 2 })
        {
          foreach (var y in new[] { 1, bounds.Height / 2, bounds.Height - 2 })
          {
            if (WindowFromPoint(new Pixel(bounds.Left + x, bounds.Top + y)) != surface)
            {
              throw new InvalidOperationException("다른 창이 펜 시험 입력면을 가리고 있습니다.");
            }
          }
        }
      }
      File.WriteAllText(Path.ChangeExtension(output, ".ready"), target.ToString());
      return;
    }
    AppWindow.Changed += (_, change) =>
    {
      if (change.DidPositionChange && !IsIconic(host))
      {
        var p = SurfaceOrigin();
        SetWindowPos(surface, 0, p.X, p.Y, 0, 0, 0x15);
      }
    };
  }

  private Pixel SurfaceOrigin()
  {
    var point = new Pixel(24, 70);
    if (!ClientToScreen(host, ref point))
    {
      throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    return point;
  }

  private nint HandleInput(nint hwnd, uint msg, nuint wParam, nint lParam, nuint subclass, nuint data)
  {
    if (hwnd == surface && msg == 0x84)
    {
      return 1; // HTCLIENT
    }

    if (msg is 0x245 or 0x246 or 0x247 or 0x249 or 0x24a)
    {
      uint id = (uint)(wParam & 0xffff);
      if (!GetPointerType(id, out uint type))
      {
        LogError("GetPointerType");
      }
      else if (type == 3)
      {
        if (!GetPointerPenInfo(id, out var pen))
        {
          LogError("GetPointerPenInfo");
        }
        else if (msg == 0x245 && pen.Pointer.History > 1)
        {
          uint count = pen.Pointer.History;
          var history = new PenInfo[count];
          if (GetPointerPenInfoHistory(id, ref count, history) && count == history.Length)
          {
            for (int i = history.Length - 1; i >= 0; i--)
            {
              Write(msg, history[i]);
            }
          }
          else
          {
            LogError("GetPointerPenInfoHistory");
          }
        }
        else
        {
          Write(msg, pen);
        }

        return 0;
      }
    }
    return DefSubclassProc(hwnd, msg, wParam, lParam);
  }

  private void Write(uint msg, PenInfo pen)
  {
    var p = pen.Pointer;
    log.WriteLine($"{msg:X}\t{p.Id}\t{p.Frame}\t{p.Position.X}\t{p.Position.Y}\t{p.Raw.X}\t{p.Raw.Y}\t{pen.Pressure}\t{pen.Mask}\t{pen.TiltX}\t{pen.TiltY}\t{pen.Flags}\t{p.Flags}\t{p.Type}");
  }
  private void LogError(string call) => log.WriteLine($"ERROR\t{call}\t{Marshal.GetLastWin32Error()}");

  private delegate nint SubclassProc(nint hwnd, uint msg, nuint wParam, nint lParam, nuint subclass, nuint data);
  [DllImport("user32")] private static extern bool SetForegroundWindow(nint hwnd);
  [DllImport("user32")] private static extern nint GetForegroundWindow();
  [DllImport("user32", SetLastError = true)] private static extern bool ClientToScreen(nint hwnd, ref Pixel p);
  [DllImport("user32")] private static extern bool IsIconic(nint hwnd);
  [DllImport("user32")] private static extern nint WindowFromPoint(Pixel point);
  [DllImport("user32", SetLastError = true)] private static extern bool SetWindowPos(nint hwnd, nint after, int x, int y, int w, int h, uint flags);
  [DllImport("user32", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern nint CreateWindowExW(uint ex, string cls, string title, uint style, int x, int y, int w, int h, nint parent, nint menu, nint instance, nint param);
  [DllImport("comctl32", SetLastError = true)] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
  [DllImport("comctl32")] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
  [DllImport("comctl32")] private static extern nint DefSubclassProc(nint hwnd, uint msg, nuint wp, nint lp);
  [DllImport("user32", SetLastError = true)] private static extern bool GetPointerType(uint id, out uint type);
  [DllImport("user32", SetLastError = true)] private static extern bool GetPointerPenInfo(uint id, out PenInfo info);
  [DllImport("user32", SetLastError = true)] private static extern bool GetPointerPenInfoHistory(uint id, ref uint count, [Out] PenInfo[] history);
}
