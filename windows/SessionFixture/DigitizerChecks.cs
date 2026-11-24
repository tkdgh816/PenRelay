using System.Text.Json;
using PenRelay.Core;

namespace PenRelay.SessionFixture;

internal static class DigitizerChecks
{
  private sealed class Sink : IPenSink
  {
    public List<PenPhase> Phases { get; } = [];
    public bool Fail { get; set; }
    public int Destroyed { get; private set; }
    public void Send(PenSample sample, PenPhase phase)
    {
      if (Fail) throw new IOException("시험용 모니터 제거");
      Phases.Add(phase);
    }
    public void Destroy() => Destroyed++;
  }

  internal static void Run()
  {
    var sink = new Sink();
    var pen = new DigitizerPen(sink);
    void Batch(params string[] actions)
    {
      var samples = actions.Select((action, i) => new { id = 0, action, sampleUs = (100+i).ToString(), u = .5, v = .5, buttons = 0 });
      pen.Accept(new(FrameKind.PenBatch, Guid.NewGuid(), 1, 0, JsonSerializer.SerializeToUtf8Bytes(new { samples })));
    }
    void Require(bool ok) { if (!ok) throw new InvalidDataException("P2-A 기대 결과 불일치"); }
    foreach (var rect in new[] { new ScreenRect(-1920, 0, 1920, 1080), new(0, -1920, 1080, 1920), new(3840, 200, 2560, 1440) })
    {
      var p = default(PenSample);
      Require(rect.Map(p) == (rect.Left, rect.Top));
      Require(rect.Map(p with { U = 1, V = 1 }) == (rect.Left + rect.Width - 1, rect.Top + rect.Height - 1));
      Require(rect.Map(p with { U = .5, V = .5 }) == (rect.Left + rect.Width / 2, rect.Top + rect.Height / 2));
    }
    for (int repeat = 0; repeat < 10; repeat++)
    {
      sink.Phases.Clear();
      Batch("move", "up", "down", "move", "cancel", "move", "up", "down", "up", "leave");
      Require(sink.Phases.SequenceEqual(new[] { PenPhase.Hover, PenPhase.Down, PenPhase.Move, PenPhase.Up,
        PenPhase.Leave, PenPhase.Hover, PenPhase.Down, PenPhase.Up, PenPhase.Leave }));
      pen.Reset();
      Batch("down");
      sink.Fail = true;
      int before = sink.Destroyed;
      try { pen.Reset(); throw new InvalidOperationException("주입 실패가 전달되지 않았습니다."); }
      catch (IOException) { Require(sink.Destroyed == before + 1); }
      sink.Fail = false;
      int count = sink.Phases.Count;
      Batch("move", "up");
      Require(sink.Phases.Count == count);
      pen.Reset();
    }
    Console.WriteLine("P2-A 모의 시험 통과 · 화면 3개 원점/중심/끝, 취소/잔류 차단/종료 실패 10회");
  }
}
