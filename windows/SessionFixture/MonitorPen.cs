using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using PenRelay.Core;
using PenRelay.Desktop;

namespace PenRelay.SessionFixture;

internal sealed class MonitorChangedException() : IOException("대상 모니터가 변경됐습니다. 모니터를 다시 선택하세요.");

public sealed record PenMonitor(string Name, string Identity, nint Handle, ScreenRect Bounds, int VirtualLeft, int VirtualTop)
{
  public override string ToString() => $"{Name} · {Bounds.Width}×{Bounds.Height} ({Bounds.Left}, {Bounds.Top})";
  public static List<PenMonitor> List()
  {
    var result = new List<PenMonitor>();
    if (!EnumDisplayMonitors(0, 0, (monitor, _, _, _) =>
    {
      var info = Info(monitor);
      var device = new DisplayDevice { Size = Marshal.SizeOf<DisplayDevice>() };
      if (info.Device != null && EnumDisplayDevicesW(info.Device, 0, ref device, 1))
        result.Add(new(info.Device, device.Id, monitor, info.Rect.Bounds, GetSystemMetrics(76), GetSystemMetrics(77)));
      return true;
    }, 0)) throw new Win32Exception();
    return result;
  }

  public bool Current() => this == List().Find(item => item.Handle == Handle);
  internal bool SameGeometry()
  {
    var info = Info(Handle);
    return info.Device == Name && info.Rect.Bounds == Bounds &&
      GetSystemMetrics(76) == VirtualLeft && GetSystemMetrics(77) == VirtualTop;
  }

  private static MonitorInfo Info(nint handle)
  {
    var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
    return GetMonitorInfoW(handle, ref info) ? info : default;
  }
  [StructLayout(LayoutKind.Sequential)]
  private struct Rect { public int Left, Top, Right, Bottom; public ScreenRect Bounds => new(Left, Top, Right - Left, Bottom - Top); }
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  private struct MonitorInfo {
    public int Size; public Rect Rect, Work; public uint Flags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Device;
  }
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  private struct DisplayDevice {
    public int Size;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Name;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
    public int Flags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Id;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Key;
  }
  private delegate bool MonitorCallback(nint monitor, nint dc, nint rect, nint data);
  [DllImport("user32", SetLastError = true)] private static extern bool EnumDisplayMonitors(nint dc, nint rect, MonitorCallback callback, nint data);
  [DllImport("user32", ExactSpelling = true)] private static extern bool GetMonitorInfoW(nint monitor, ref MonitorInfo info);
  [DllImport("user32", ExactSpelling = true, CharSet = CharSet.Unicode)] private static extern bool EnumDisplayDevicesW(string device, uint index, ref DisplayDevice info, uint flags);
  [DllImport("user32")] private static extern int GetSystemMetrics(int index);
  [DllImport("user32", SetLastError = true)] internal static extern bool SetProcessDpiAwarenessContext(nint context);
}

internal sealed class MonitorPen(PenMonitor monitor) : IPenSink
{
  private nint device;
  private long lastInjection;
  private PenPhase? previous;
  public void Send(PenSample sample, PenPhase phase)
  {
    if (!monitor.SameGeometry() || (phase == PenPhase.Down && !monitor.Current()))
      throw new MonitorChangedException();
    if (device == 0)
    {
      device = CreateSyntheticPointerDevice(3, 1, 3);
      if (device == 0) throw new Win32Exception();
    }
    var (x, y) = monitor.Bounds.Map(sample);
    var pen = new PenInfo {
      Pointer = new PointerInfo { Type = 3, Id = 1,
        Position = new Pixel(x - monitor.VirtualLeft, y - monitor.VirtualTop),
        Flags = phase switch { PenPhase.Down => 0x10006u, PenPhase.Move => 0x20006u,
          PenPhase.Up => 0x40002u, PenPhase.Leave => 0x20000u, _ => 0x20002u } },
      Flags = (uint)sample.Buttons
    };
    if (sample.Pressure is double pressure) { pen.Mask |= 1; pen.Pressure = (uint)Math.Round(pressure * 1024); }
    if (sample.Tilt is double tilt && sample.Orientation is double angle)
    {
      double tan = Math.Tan(Math.Min(tilt, Math.PI / 2));
      pen.Mask |= 12;
      pen.TiltX = (int)Math.Round(Math.Atan(tan * Math.Sin(angle)) * 180 / Math.PI);
      pen.TiltY = (int)Math.Round(Math.Atan(-tan * Math.Cos(angle)) * 180 / Math.PI);
    }
    var packet = new PointerTypeInfo { Type = 3, Pen = pen };
    for (int attempt = 0; ; attempt++)
    {
      // history 한 묶음도 서로 다른 고해상도 입력 시각을 갖게 한다.
      while (Stopwatch.GetElapsedTime(lastInjection).TotalMicroseconds < 150)
        Thread.SpinWait(32);
      if (InjectSyntheticPointerInput(device, ref packet, 1))
      {
        lastInjection = Stopwatch.GetTimestamp();
        previous = phase;
        break;
      }
      int code = Marshal.GetLastWin32Error();
      if (code != 21 || attempt == 3)
        throw new Win32Exception(code, $"PT_PEN {previous} → {phase} ({sample.Action}) 오류 {code}, mask={pen.Mask}, 버튼={pen.Flags}, 압력={pen.Pressure}, 기울기=({pen.TiltX},{pen.TiltY})");
      Thread.Sleep(1);
    }
  }

  public void Destroy()
  {
    if (device != 0) DestroySyntheticPointerDevice(device);
    device = 0;
    previous = null;
  }

  [DllImport("user32", SetLastError = true)] private static extern nint CreateSyntheticPointerDevice(uint type, uint count, uint feedback);
  [DllImport("user32", SetLastError = true)] private static extern bool InjectSyntheticPointerInput(nint device, ref PointerTypeInfo data, uint count);
  [DllImport("user32")] private static extern void DestroySyntheticPointerDevice(nint device);
}
