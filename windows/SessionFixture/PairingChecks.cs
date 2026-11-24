using PenRelay.Core;

namespace PenRelay.SessionFixture;

internal static class PairingChecks
{
  private sealed class Clock : TimeProvider
  {
    internal long Ticks;
    public override long TimestampFrequency => TimeSpan.TicksPerSecond;
    public override long GetTimestamp() => Ticks;
  }

  public static async Task RunAsync()
  {
    var directory = Directory.CreateTempSubdirectory("penrelay-trust-test-");
    try
    {
      var clock = new Clock();
      using var trust = new DeviceTrust(directory.FullName, clock);
      string first = new('A', 64), second = new('B', 64);
      Task<bool> Accept(string _, CancellationToken __) => Task.FromResult(true);
      Task<bool> Unexpected(string _, CancellationToken __) => throw new Exception("예상하지 않은 승인 요청");
      var invitation = trust.OpenPairing();
      clock.Ticks += DeviceTrust.PairingLifetime.Ticks;
      Check(!await trust.RegisterAsync(invitation.Secret, first, Unexpected), "만료 경계");
      invitation = trust.OpenPairing();
      trust.CancelPairing();
      Check(!await trust.RegisterAsync(invitation.Secret, first, Unexpected), "취소된 비밀");
      invitation = trust.OpenPairing();
      var wrong = invitation.Secret.ToArray();
      wrong[0] ^= 1;
      for (int i = 0; i < DeviceTrust.FailureLimit; i++)
        Check(!await trust.RegisterAsync(wrong, first, Unexpected), "잘못된 비밀");
      Check(!await trust.RegisterAsync(invitation.Secret, first, Unexpected), "실패 한도");

      invitation = trust.OpenPairing();
      Check(!await trust.RegisterAsync(invitation.Secret, first, (_, _) => Task.FromResult(false)), "PC 거절");
      Check(!await trust.RegisterAsync(invitation.Secret, first, Unexpected), "거절 후 재사용");

      foreach (var outcome in new[] { "승인", "만료", "취소", "교체", "폐기" })
      {
        invitation = trust.OpenPairing();
        var decision = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var pending = trust.RegisterAsync(invitation.Secret, first, (_, _) => decision.Task);
        Check(!await trust.RegisterAsync(invitation.Secret, second, Unexpected), "동시 요청");
        switch (outcome)
        {
          case "만료": clock.Ticks += DeviceTrust.PairingLifetime.Ticks; break;
          case "취소": trust.CancelPairing(); break;
          case "교체": trust.OpenPairing(); break;
          case "폐기": trust.Revoke(first); break;
        }
        decision.SetResult(true);
        Check(await pending == (outcome == "승인"), "승인 대기 중 " + outcome);
        Check(!await trust.RegisterAsync(invitation.Secret, second, Unexpected), "이전 비밀 재사용");
      }

      var fresh = trust.OpenPairing();
      Check(await trust.RegisterAsync(fresh.Secret, first, Accept), "새 비밀 재등록");
      fresh = trust.OpenPairing();
      Check(await trust.RegisterAsync(fresh.Secret, second, Accept), "다른 키 등록");
      Check(trust.Revoke(first) && !trust.Revoke(first), "반복 폐기");
      Check(File.ReadAllLines(Path.Combine(directory.FullName, "trusted-keys.txt")).SequenceEqual([second]),
        "대상 키만 폐기");
      Console.WriteLine("2분 만료·취소·실패 한도·PC 거절·동시 등록·지연 승인·비밀 재사용·재등록·선택 폐기 통과");
    }
    finally
    {
      File.Delete(Path.Combine(directory.FullName, "trusted-keys.txt"));
      File.Delete(Path.Combine(directory.FullName, "owner.lock"));
      directory.Delete();
    }
  }

  private static void Check(bool condition, string test)
  {
    if (!condition) throw new InvalidOperationException(test);
  }
}
