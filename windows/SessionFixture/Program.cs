using PenRelay.SessionFixture;
using System.Net;

if (args is ["canvas-checks"])
{
  CanvasChecks.Run();
  return 0;
}

if (args is ["pen-checks"])
{
  DigitizerChecks.Run();
  return 0;
}

if (OperatingSystem.IsWindows() && args.FirstOrDefault() is "monitors" or "digitizer")
{
  if (!Environment.Is64BitProcess || !PenMonitor.SetProcessDpiAwarenessContext(-4))
    throw new InvalidOperationException("x64 Per-Monitor V2 실행이 필요합니다.");
  var monitors = PenMonitor.List();
  if (args is ["monitors"])
  {
    foreach (var monitor in monitors) Console.WriteLine(monitor);
    return 0;
  }
  if (args.Length is >= 5 and <= 7 && IPAddress.TryParse(args[1], out var ip)
    && int.TryParse(args[2], out var sessionPort) && int.TryParse(args[3], out var pairingPort))
  {
    var monitor = monitors.SingleOrDefault(m => m.Name == args[4]);
    if (monitor == null || (args.Length >= 6 && args[5] != "--temporary" && monitor.Identity != args[5]))
      throw new ArgumentException("선택한 모니터가 변경되었습니다.");
    Console.WriteLine("대상 " + monitor);
    await LanHost.Run(ip, sessionPort, pairingPort, args[^1] == "--temporary", monitor);
    return 0;
  }
  Console.Error.WriteLine("digitizer <PC LAN 주소> <세션 포트> <등록 포트> <모니터 이름>");
  return 2;
}

if (OperatingSystem.IsWindows() && args.Length is 4 or 5 && args[0] == "serve"
  && IPAddress.TryParse(args[1], out var address) && int.TryParse(args[2], out var port)
  && int.TryParse(args[3], out var pairPort) && (args.Length == 4 || args[4] == "--temporary"))
{
  await LanHost.Run(address, port, pairPort, args.Length == 5);
  return 0;
}

if (args is ["vectors", var path])
{
  VectorChecks.Run(path);
  return 0;
}
if (args is ["pairing-checks"])
{
  await PairingChecks.RunAsync();
  return 0;
}
if (args is ["loopback"] && Console.IsOutputRedirected && Console.IsInputRedirected)
{
  await LoopbackHost.RunAsync();
  return 0;
}
Console.Error.WriteLine("사용법: SessionFixture vectors <TSV 경로> 또는 pairing-checks. loopback은 Kotlin 시험에서 실행합니다.");
return 2;
