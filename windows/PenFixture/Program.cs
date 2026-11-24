using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using PenRelay.Desktop;

internal static partial class Program
{
  private const uint InRange = 2, Contact = 4, Down = 0x10000, Update = 0x20000, Up = 0x40000;
  private const int Steps = 30, StepPixels = 4, RowPixels = 48;

  private static int Main(string[] args)
  {
    try
    {
      bool recovery = args is ["--recovery-host", _, _];
      string? reconnectPipe = recovery ? args[2] : null;
      if (recovery) args = [args[1], "80", "105", ""];
      if (args.Length != 4)
        throw new ArgumentException("사용법: PenFixture <대상 PID> <클라이언트 x> <클라이언트 y> <새 송신 TSV>");
      using var target = Process.GetProcessById(int.Parse(args[0]));
      bool receiver = target.ProcessName == "PenRelay.Desktop" && target.MainWindowTitle == "P0-W · 펜 수신";
      bool drawingApp = (target.ProcessName.Equals("mspaint", StringComparison.OrdinalIgnoreCase)
        || target.ProcessName.Equals("krita", StringComparison.OrdinalIgnoreCase))
        && target.MainWindowTitle.Contains("P0-W-pressure", StringComparison.OrdinalIgnoreCase);
      if (!receiver && (!drawingApp || recovery))
        throw new InvalidOperationException("P0-W 수신 창 또는 P0-W-pressure 시험 이미지를 연 그림판이나 Krita를 선택하세요.");
      if (!SetProcessDpiAwarenessContext(-4)) throw Error("SetProcessDpiAwarenessContext");
      nint window = target.MainWindowHandle;
      var start = new Pixel(int.Parse(args[1]), int.Parse(args[2]));
      if (!ClientToScreen(window, ref start)) throw Error("ClientToScreen");
      int left = GetSystemMetrics(76), top = GetSystemMetrics(77);
      uint[] pressures = [128, 512, 896];
      var timer = Stopwatch.StartNew();
      while (GetAncestor(GetForegroundWindow(), 3) != window && timer.Elapsed < TimeSpan.FromSeconds(30)) Thread.Sleep(100);
      nint surface = receiver ? WindowFromPoint(start) : window;
      if (receiver && (WindowTitle(surface) != "P0-W input" || GetAncestor(surface, 3) != window))
        throw new InvalidOperationException("시작점이 수신 창의 흰색 시험면 밖에 있습니다.");
      void CheckTarget(Pixel point)
      {
        var front = GetForegroundWindow();
        if ((front != window && front != surface) || GetAncestor(WindowFromPoint(point), 2) != surface
          || (drawingApp && !WindowTitle(window).Contains("P0-W-pressure", StringComparison.OrdinalIgnoreCase)))
          throw new InvalidOperationException("대상 창, 시험 문서 또는 입력면이 바뀌어 중단했습니다.");
      }
      void Inject(nint device, Pixel point, uint flags, uint pressure, int tilt)
      {
        CheckTarget(point);
        var packet = new PointerTypeInfo { Type = 3, Pen = new PenInfo {
          Pointer = new PointerInfo { Type = 3, Id = 1, Flags = flags,
            Position = new Pixel(point.X - left, point.Y - top) },
          Pressure = pressure, Mask = 13, TiltX = tilt, TiltY = -tilt } };
        if (!InjectSyntheticPointerInput(device, ref packet, 1)) throw Error("InjectSyntheticPointerInput");
      }
      if (reconnectPipe != null)
      {
        RunRecoveryHost(start, reconnectPipe, Inject);
        return 0;
      }
      using var log = new StreamWriter(new FileStream(args[3], FileMode.CreateNew, FileAccess.Write, FileShare.Read)) { AutoFlush = true };
      log.WriteLine("event\tx\ty\tinjectX\tinjectY\tpressure\tmask\ttiltX\ttiltY\tflags");
      for (int row = 0; row < pressures.Length; row++)
        for (int step = 0; step <= Steps; step++)
          CheckTarget(new Pixel(start.X + step * StepPixels, start.Y + row * RowPixels));
      var hitClass = new StringBuilder(256);
      GetClassNameW(WindowFromPoint(start), hitClass, hitClass.Capacity);
      Console.WriteLine($"입력면: {hitClass}, 화면 시작점 ({start.X}, {start.Y})");
      nint device = CreateSyntheticPointerDevice(3, 1, 3);
      if (device == 0) throw Error("CreateSyntheticPointerDevice");
      try
      {
        for (int row = 0; row < pressures.Length; row++)
        {
          int y = start.Y + RowPixels * row;
          int tilt = receiver ? (row == 1 ? 30 : row == 2 ? -30 : 0) : 0;
          void Send(string name, int step, uint flags, uint pressure)
          {
            var point = new Pixel(start.X + step * StepPixels, y);
            Inject(device, point, flags, pressure, tilt);
            var position = new Pixel(point.X - left, point.Y - top);
            log.WriteLine($"{name}\t{point.X}\t{point.Y}\t{position.X}\t{position.Y}\t{pressure}\t13\t{tilt}\t{-tilt}\t{flags}");
            Thread.Sleep(16);
          }
          Send("hover", 0, Update | InRange, 0);
          Send("down", 0, Down | InRange | Contact, pressures[row]);
          for (int step = 1; step <= Steps; step++)
            Send("move", step, Update | InRange | Contact, pressures[row]);
          Send("up", Steps, Up | InRange, 0);
          Send("leave", Steps, Update, 0);
          Thread.Sleep(100);
        }
      }
      finally { DestroySyntheticPointerDevice(device); }
      Console.WriteLine("세 획 주입 완료");
      return 0;
    }
    catch (Exception ex) { Console.Error.WriteLine(ex.Message); return 1; }
  }

  private static string WindowTitle(nint window)
  {
    var title = new StringBuilder(512);
    GetWindowTextW(window, title, title.Capacity);
    return title.ToString();
  }

  private static Exception Error(string call) => new Win32Exception(Marshal.GetLastWin32Error(), $"{call} 실패 ({Marshal.GetLastWin32Error()})");
  [DllImport("user32", SetLastError = true)] private static extern bool SetProcessDpiAwarenessContext(nint context);
  [DllImport("user32", SetLastError = true)] private static extern bool ClientToScreen(nint window, ref Pixel point);
  [DllImport("user32")] private static extern int GetSystemMetrics(int index);
  [DllImport("user32")] private static extern nint WindowFromPoint(Pixel point);
  [DllImport("user32", CharSet = CharSet.Unicode, ExactSpelling = true)] private static extern int GetClassNameW(nint window, StringBuilder name, int capacity);
  [DllImport("user32", CharSet = CharSet.Unicode, ExactSpelling = true)] private static extern int GetWindowTextW(nint window, StringBuilder name, int capacity);
  [DllImport("user32")] private static extern nint GetAncestor(nint window, uint flags);
  [DllImport("user32")] private static extern nint GetForegroundWindow();
  [DllImport("user32", SetLastError = true)] private static extern nint CreateSyntheticPointerDevice(uint type, uint count, uint feedback);
  [DllImport("user32", SetLastError = true)] private static extern bool InjectSyntheticPointerInput(nint device, ref PointerTypeInfo data, uint count);
  [DllImport("user32")] private static extern void DestroySyntheticPointerDevice(nint device);
}
