using System;
using System.IO;
using System.Runtime.InteropServices;

namespace PenRelay.Desktop;

internal sealed class AppTrayIcon : IDisposable
{
  private const uint CallbackMessage = 0x8000 + 93, IconId = 1;
  private readonly nint window;
  private readonly Action restore, quit;
  private readonly SubclassProc callback;
  private readonly uint taskbarCreated = RegisterWindowMessageW("TaskbarCreated");
  private IconData data;
  private bool registered, version4;
  private const uint WmSysCommand = 0x0112, ScMinimize = 0xF020;
  public Func<bool>? MinimizeToTray { get; set; }

  public AppTrayIcon(nint window, Action restore, Action quit)
  {
    this.window = window;
    this.restore = restore;
    this.quit = quit;
    callback = HandleMessage;
    data = new IconData
    {
      Size = (uint)Marshal.SizeOf<IconData>(), Window = window, Id = IconId,
      Flags = 1 | 2 | 4 | 128, Message = CallbackMessage, Tip = App.ProductName,
      Icon = LoadImageW(0, Path.Combine(AppContext.BaseDirectory, "Assets", "PenRelay.ico"), 1, 0, 0, 0x10 | 0x40)
    };
    if (data.Icon != 0 && SetWindowSubclass(window, callback, IconId, 0))
    {
      EnsureRegistered();
    }
    else if (data.Icon != 0)
    {
      DestroyIcon(data.Icon);
      data.Icon = 0;
    }
  }

  public bool EnsureRegistered()
  {
    if (!registered && data.Icon != 0)
    {
      registered = Shell_NotifyIconW(0, ref data);
      if (registered)
      {
        data.Version = 4;
        version4 = Shell_NotifyIconW(4, ref data);
      }
    }
    return registered;
  }

  private nint HandleMessage(nint hwnd, uint message, nuint wParam, nint lParam, nuint id, nuint context)
  {
    if (message == WmSysCommand && (wParam & 0xfff0) == ScMinimize && MinimizeToTray?.Invoke() == true) return 0;
    if (message == taskbarCreated)
    {
      registered = false;
      if (!EnsureRegistered()) restore();
    }
    if (message == CallbackMessage)
    {
      int action = (int)(lParam & 0xffff);
      if (version4 ? action is 0x400 or 0x401 : action == 0x202)
      {
        restore();
      }
      else if (version4 ? action == 0x7b : action == 0x205)
      {
        var point = new Point { X = unchecked((short)wParam), Y = unchecked((short)(wParam >> 16)) };
        if (!version4 || (point.X == -1 && point.Y == -1))
        {
          GetCursorPos(out point);
        }
        ShowMenu(point);
      }
      return 0;
    }
    return DefSubclassProc(hwnd, message, wParam, lParam);
  }

  private void ShowMenu(Point point)
  {
    var menu = CreatePopupMenu();
    if (menu == 0) return;
    uint command;
    try
    {
      AppendMenuW(menu, 0, 1, "펜 릴레이 열기");
      AppendMenuW(menu, 0, 2, "종료");
      SetForegroundWindow(window);
      command = TrackPopupMenuEx(menu, 0x100 | 0x2, point.X, point.Y, window, 0);
      PostMessageW(window, 0, 0, 0);
    }
    finally
    {
      DestroyMenu(menu);
    }
    if (command == 1) restore();
    if (command == 2) quit();
  }

  public void Dispose()
  {
    if (data.Icon == 0) return;
    if (registered) Shell_NotifyIconW(2, ref data);
    RemoveWindowSubclass(window, callback, IconId);
    DestroyIcon(data.Icon);
    data.Icon = 0;
    registered = false;
  }

  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  private struct IconData
  {
    public uint Size;
    public nint Window;
    public uint Id, Flags, Message;
    public nint Icon;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Tip;
    public uint State, StateMask;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string? Info;
    public uint Version;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string? InfoTitle;
    public uint InfoFlags;
    public Guid Guid;
    public nint BalloonIcon;
  }

  [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
  private delegate nint SubclassProc(nint hwnd, uint message, nuint wParam, nint lParam, nuint id, nuint context);
  [DllImport("comctl32")] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc callback, nuint id, nuint context);
  [DllImport("comctl32")] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc callback, nuint id);
  [DllImport("comctl32")] private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wParam, nint lParam);
  [DllImport("shell32", ExactSpelling = true)] private static extern bool Shell_NotifyIconW(uint operation, ref IconData data);
  [DllImport("user32", CharSet = CharSet.Unicode)] private static extern uint RegisterWindowMessageW(string name);
  [DllImport("user32", CharSet = CharSet.Unicode)] private static extern nint LoadImageW(nint instance, string file, uint type, int width, int height, uint flags);
  [DllImport("user32")] private static extern bool DestroyIcon(nint icon);
  [DllImport("user32")] private static extern bool GetCursorPos(out Point point);
  [DllImport("user32")] private static extern nint CreatePopupMenu();
  [DllImport("user32", CharSet = CharSet.Unicode)] private static extern bool AppendMenuW(nint menu, uint flags, nuint id, string label);
  [DllImport("user32")] internal static extern bool SetForegroundWindow(nint hwnd);
  [DllImport("user32")] private static extern uint TrackPopupMenuEx(nint menu, uint flags, int x, int y, nint owner, nint options);
  [DllImport("user32")] private static extern bool PostMessageW(nint hwnd, uint message, nuint wParam, nint lParam);
  [DllImport("user32")] private static extern bool DestroyMenu(nint menu);
}
