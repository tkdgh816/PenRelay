using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace PenRelay.Desktop;

internal sealed class UsbPortBridge
{
  private readonly string adb;
  private string? serial;
  private readonly HashSet<string> owned = new();
  private readonly string[] ports;

  public UsbPortBridge(string? selected, int sessionPort, int pairingPort)
  {
    ports = [$"tcp:{sessionPort}", $"tcp:{pairingPort}"];
    adb = ResolveAdb(selected);
  }

  internal static string ResolveAdb(string? selected)
  {
    if (!string.IsNullOrWhiteSpace(selected))
    {
      if (!Path.IsPathFullyQualified(selected) || !File.Exists(selected) ||
          !string.Equals(Path.GetFileName(selected), "adb.exe", StringComparison.OrdinalIgnoreCase))
      {
        throw new FileNotFoundException("설정에서 Android Platform Tools의 adb.exe를 다시 선택하세요.");
      }

      return selected;
    }
    var candidates = new List<string>();
    foreach (var name in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
    {
      if (Environment.GetEnvironmentVariable(name) is string root)
      {
        candidates.Add(Path.Combine(root, "platform-tools", "adb.exe"));
      }
    }

    candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Android", "Sdk", "platform-tools", "adb.exe"));
    foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
    {
      if (Path.IsPathFullyQualified(dir))
      {
        candidates.Add(Path.Combine(dir, "adb.exe"));
      }
    }

    return candidates.Where(Path.IsPathFullyQualified).FirstOrDefault(File.Exists) ?? throw new FileNotFoundException(
            "설정에서 Android Platform Tools의 adb.exe를 선택한 뒤 호스트를 다시 시작하세요.");
  }

  private async Task<string> Call(params string[] args)
  {
    var start = new ProcessStartInfo(adb)
    {
      UseShellExecute = false,
      CreateNoWindow = true,
      RedirectStandardOutput = true,
      RedirectStandardError = true
    };
    foreach (var arg in args)
    {
      start.ArgumentList.Add(arg);
    }

    using var process = Process.Start(start) ?? throw new IOException("ADB를 시작하지 못했습니다.");
    var output = process.StandardOutput.ReadToEndAsync();
    var error = process.StandardError.ReadToEndAsync();
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(6));
    try
    {
      await process.WaitForExitAsync(timeout.Token);
    }
    catch (OperationCanceledException)
    {
      process.Kill();
      throw new IOException("ADB 응답 시간 초과");
    }
    if (process.ExitCode != 0)
    {
      throw new IOException((await error).Trim());
    }

    return (await output).Trim();
  }

  private async Task<Dictionary<string, string>> Mappings()
  {
    var rows = (await Call("-s", serial!, "reverse", "--list")).Split('\n', StringSplitOptions.RemoveEmptyEntries);
    return rows.Select(row => row.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))
        .Where(parts => parts.Length >= 2).ToDictionary(parts => parts[^2], parts => parts[^1]);
  }

  public async Task Ensure()
  {
    string device = await Call("-d", "get-serialno");
    if (device.Length == 0 || device == "unknown")
    {
      throw new IOException("승인된 USB 태블릿을 연결하세요.");
    }

    serial ??= device;
    if (serial != device)
    {
      throw new IOException("처음 선택한 태블릿을 다시 연결하세요.");
    }

    var map = await Mappings();
    foreach (var port in ports)
    {
      if (map.TryGetValue(port, out string? destination))
      {
        if (destination != port)
        {
          throw new IOException($"다른 ADB 연결이 {port}를 사용하고 있습니다.");
        }

        continue;
      }
      await Call("-s", serial, "reverse", "--no-rebind", port, port);
      owned.Add(port);
    }
  }

  public async Task Monitor(CancellationToken token, Action<string> report)
  {
    string previous = "";
    while (true)
    {
      await Task.Delay(1500, token);
      string message;
      try
      {
        await Ensure();
        message = "USB 포트 준비";
      }
      catch (IOException ex)
      {
        message = "USB 재연결 대기 · " + ex.Message;
      }
      if (message != previous)
      {
        report(message);
      }

      previous = message;
    }
  }

  public async Task Close()
  {
    if (owned.Count == 0)
    {
      return;
    }

    try
    {
      var map = await Mappings();
      foreach (var port in owned)
      {
        if (map.GetValueOrDefault(port) == port)
        {
          await Call("-s", serial!, "reverse", "--remove", port);
        }
      }
    }
    catch (IOException) { /* 케이블이 분리되면 기존 전송의 매핑도 제거된다. */ }
  }
}
