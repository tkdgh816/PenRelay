using System.IO.Pipes;
using PenRelay.Desktop;

internal static partial class Program
{
  // Fixed local coordinates only. The second anonymous pipe belongs to the same test runner.
  private static void RunRecoveryHost(Pixel origin, string reconnectHandle, Action<nint, Pixel, uint, uint, int> inject)
  {
    using var reconnect = new AnonymousPipeClientStream(PipeDirection.In, reconnectHandle);
    using var nextInput = new StreamReader(reconnect);
    nint device = 0;
    bool digitizer = true, contact = false;
    var last = origin;
    void Send(Pixel point, uint flags, uint pressure)
    {
      if (device == 0)
      {
        device = CreateSyntheticPointerDevice(3, 1, 3);
        if (device == 0) throw Error("CreateSyntheticPointerDevice");
      }
      inject(device, point, flags, pressure, 0);
      last = point;
      contact = (flags & Contact) != 0;
      Thread.Sleep(16);
    }
    void EndContact()
    {
      if (contact) Send(last, Up, 0);
    }
    void RemoveDevice()
    {
      if (device != 0) DestroySyntheticPointerDevice(device);
      device = 0;
      contact = false;
    }
    bool ReadSession(TextReader input)
    {
      string? command;
      while ((command = input.ReadLine()) != null)
      {
        string reply = "ok";
        var second = new Pixel(origin.X + 140, origin.Y + 68);
        switch (command)
        {
          case "first":
          case "second":
            if (!digitizer) { reply = "blocked"; break; }
            if (contact) throw new InvalidOperationException("중복 DOWN");
            var point = command == "first" ? origin : second;
            Send(point, Update | InRange, 0);
            Send(point, Down | InRange | Contact, 512);
            Send(new Pixel(point.X + 24, point.Y), Update | InRange | Contact, 512);
            break;
          case "late-move":
            if (!digitizer || !contact) { reply = "blocked"; break; }
            Send(second, Update | InRange | Contact, 512);
            break;
          case "hover":
            if (!digitizer || contact) throw new InvalidOperationException("호버 전 상태 오류");
            Send(new Pixel(second.X - 20, second.Y), Update | InRange, 0);
            Send(second, Update | InRange, 0);
            break;
          case "cancel":
            RemoveDevice();
            break;
          case "canvas":
            EndContact();
            digitizer = false;
            break;
          case "digitizer": digitizer = true; break;
          case "up": EndContact(); break;
          case "quit": return false;
          default: throw new ArgumentException("알 수 없는 시험 명령");
        }
        Console.WriteLine(reply);
      }
      EndContact();
      return true;
    }
    try
    {
      Console.WriteLine($"ready {origin.X} {origin.Y}");
      if (ReadSession(Console.In))
      {
        Console.WriteLine("disconnected");
        ReadSession(nextInput);
      }
    }
    finally
    {
      // Device teardown must also run when a terminal injection fails.
      try { EndContact(); }
      finally { RemoveDevice(); }
    }
  }
}
