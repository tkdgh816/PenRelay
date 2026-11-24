using System;
using System.IO;
using System.Text.Json;

namespace PenRelay.Desktop;

public enum ConnectionRoute { Wifi = 0, Reverse = 1, Tether = 2, Direct = 3 }
public sealed class HostPreferences
{
  private static string DirectoryPath => Path.Combine(Environment.GetFolderPath(
      Environment.SpecialFolder.LocalApplicationData), "PenRelay");
  private static string SettingsPath => Path.Combine(DirectoryPath, "settings.json");
  private string? savedJson;
  public ConnectionRoute Route { get; set; }
  public int SessionPort { get; set; } = 47120;
  public int PairingPort { get; set; } = 47121;
  public bool HasSuccessfulSession { get; set; }
  public string? LastTablet { get; set; }
  public string? WifiAddress { get; set; }
  public string? TetherAddress { get; set; }
  public string? MonitorId { get; set; }
  public string? MonitorName { get; set; }
  public bool HideOnClose { get; set; } = true;
  public bool HideOnMinimize { get; set; }
  public int Theme { get; set; }
  public string? AdbPath { get; set; }
  public bool KeepAspect { get; set; } = true;
  public int QuarterTurns { get; set; }

  public static HostPreferences Load(out string? warning)
  {
    warning = null;
    try
    {
      var settings = JsonSerializer.Deserialize<HostPreferences>(File.ReadAllText(SettingsPath)) ?? new();
      settings.savedJson = JsonSerializer.Serialize(settings);
      // 이전 코드 전용 옵션은 숫자 4로 저장됐다.
      if ((int)settings.Route == 4) settings.Route = ConnectionRoute.Direct;
      return settings;
    }
    catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
    {
      return new();
    }
    catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
    {
      warning = "설정을 읽지 못해 기본값을 사용합니다. " + ex.Message;
      return new();
    }
  }

  public void Save()
  {
    string json = JsonSerializer.Serialize(this);
    if (json == savedJson) return;
    Directory.CreateDirectory(DirectoryPath);
    string temp = Path.Combine(DirectoryPath, Guid.NewGuid() + ".tmp");
    try
    {
      File.WriteAllText(temp, json);
      File.Move(temp, SettingsPath, true);
      savedJson = json;
    }
    finally
    {
      File.Delete(temp);
    }
  }
}
