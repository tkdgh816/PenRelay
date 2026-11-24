using PenRelay.Core;

namespace PenRelay.SessionFixture;

public sealed class HostTelemetry
{
  private readonly object gate = new();
  private readonly Queue<double> batches = new();
  private long samples;
  private bool pressure, tilt;
  public Action<string>? ModeChanged { get; init; }
  public Action<string,string,bool>? PeerConnected { get; init; }
  public Func<string,PageLibrary>? OpenLibrary { get; init; }
  public Func<PenMappingOptions>? Mapping { get; init; }
  public void Connected(string pin, string name, bool pages) => PeerConnected?.Invoke(pin,name,pages);
  public void Observe(PenSample sample)
  {
    lock (gate)
    {
      samples++;
      pressure |= sample.Pressure.HasValue;
      tilt |= sample.Tilt.HasValue && sample.Orientation.HasValue;
    }
  }
  public void Batch(double ms)
  {
    lock (gate)
    {
      if (batches.Count == 256) batches.Dequeue();
      batches.Enqueue(ms);
    }
  }
  public string Summary()
  {
    lock (gate)
    {
      string Axis(bool seen) => seen ? "수신" : "미관찰";
      string summary = $"펜 표본 {samples:N0}개 · 압력 {Axis(pressure)} · 기울기 {Axis(tilt)}";
      if (batches.Count == 0) return summary;
      var sorted = batches.Order().ToArray();
      double Percentile(double p) => sorted[(int)Math.Ceiling(sorted.Length * p) - 1];
      return summary + $"\nWindows 펜 배치 처리 시간 · 최근 {sorted.Length}개 · p50 {Percentile(.5):F2} / p95 {Percentile(.95):F2} / p99 {Percentile(.99):F2} ms";
    }
  }
}
