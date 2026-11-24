using System.Globalization;
using System.Text.Json;

namespace PenRelay.Core;

public readonly record struct PenSample(int Id, string Action, ulong Time, double U, double V,
  int Buttons, double? Pressure, double? Tilt, double? Orientation)
{
  public static PenSample[] Parse(byte[] payload)
  {
    using var json = JsonDocument.Parse(payload);
    var samples = json.RootElement.GetProperty("samples");
    if (samples.ValueKind != JsonValueKind.Array || samples.GetArrayLength() is < 1 or > 128)
      throw new InvalidDataException("펜 표본 개수 오류");
    var result = new PenSample[samples.GetArrayLength()];
    int index = 0;
    foreach (var point in samples.EnumerateArray())
    {
      double Number(JsonElement value, double min, double max)
      {
        if (!value.TryGetDouble(out var n) || !double.IsFinite(n) || n < min || n > max)
          throw new InvalidDataException("펜 축 범위 오류");
        return n;
      }
      double? Axis(string key, double min, double max) =>
        point.TryGetProperty(key, out var value) ? Number(value, min, max) : null;
      var action = point.GetProperty("action").GetString();
      int id = point.GetProperty("id").GetInt32(), buttons = point.GetProperty("buttons").GetInt32();
      if (action is not ("enter" or "hover" or "down" or "move" or "up" or "leave" or "cancel" or "button")
        || id < 0 || buttons is < 0 or > 1 || !ulong.TryParse(point.GetProperty("sampleUs").GetString(),
          NumberStyles.None, CultureInfo.InvariantCulture, out var time))
        throw new InvalidDataException("잘못된 펜 표본");
      result[index++] = new(id, action, time, Number(point.GetProperty("u"), 0, 1),
        Number(point.GetProperty("v"), 0, 1), buttons, Axis("pressure", 0, 1),
        Axis("tilt", 0, Math.PI / 2 + 1e-6), Axis("orientation", -Math.PI - 1e-6, Math.PI + 1e-6));
    }
    return result;
  }
}

public readonly record struct ScreenRect(int Left, int Top, int Width, int Height)
{
  public (int X, int Y) Map(PenSample p) => (Left + (int)Math.Round(p.U * (Width - 1)),
    Top + (int)Math.Round(p.V * (Height - 1)));
}

public enum PenPhase { Hover, Down, Move, Up, Leave }
public interface IPenSink
{
  void Send(PenSample sample, PenPhase phase);
  void Destroy();
}

// 한 인증 세션의 수신 스레드가 소유한다. 모드 ACK 전과 세션 종료 시 Reset을 호출한다.
public sealed class DigitizerPen(IPenSink sink, Action<PenSample>? observed = null)
{
  private PenSample last;
  private bool inRange, contact;
  public bool InContact => contact;
  private ulong previousTime;

  public void Accept(Frame frame)
  {
    foreach (var sample in PenSample.Parse(frame.Payload))
    {
      if (sample.Time < previousTime) throw new InvalidDataException("펜 표본 시간 역행");
      previousTime = sample.Time;
      if (inRange && last.Id != sample.Id) throw new InvalidDataException("펜 ID 변경");
      observed?.Invoke(sample);
      switch (sample.Action)
      {
        case "cancel": Reset(); break;
        case "leave": Reset(); break;
        case "enter" or "hover":
          if (contact) throw new InvalidDataException("접촉 중 호버");
          Emit(sample, PenPhase.Hover);
          break;
        case "down":
          if (contact) throw new InvalidDataException("중복 DOWN");
          if (!inRange) Emit(sample with { Buttons = 0, Pressure = null }, PenPhase.Hover);
          Emit(sample, PenPhase.Down);
          break;
        case "move" when contact: Emit(sample, PenPhase.Move); break;
        case "up" when contact:
          if (sample.U != last.U || sample.V != last.V)
            Emit(sample with { Pressure = last.Pressure }, PenPhase.Move);
          Emit(sample, PenPhase.Up);
          break;
        case "button" when inRange: Emit(sample, contact ? PenPhase.Move : PenPhase.Hover); break;
      }
    }
  }

  private void Emit(PenSample p, PenPhase phase)
  {
    sink.Send(p, phase);
    last = p;
    inRange = phase != PenPhase.Leave;
    contact = phase is PenPhase.Down or PenPhase.Move;
  }

  public void Reset()
  {
    try
    {
      var released = last with { Buttons = 0, Pressure = 0 };
      if (contact) Emit(released, PenPhase.Up);
      if (inRange) Emit(released, PenPhase.Leave);
    }
    finally
    {
      sink.Destroy();
      inRange = contact = false;
      previousTime = 0;
    }
  }
}
