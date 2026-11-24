using System.Diagnostics;
using System.Globalization;
using System.Text.Json;

namespace PenRelay.Core;

public sealed record PenMappingOptions(string MonitorKey, bool KeepAspect, int QuarterTurns, bool Calibrate, string? MonitorName = null);

// P1 제어 채널은 입력 주입기나 캔버스 처리기를 소유하지 않는다.
public static class SessionControl
{
  public static void Run(TrustedSession session, CancellationToken cancellation,
    Action<Frame>? digitizer = null, Action<Frame>? canvas = null,
    Action? endDigitizer = null, ScreenRect? target = null, Action? checkTarget = null, CanvasReplica? replica = null,
    Action<Frame>? afterCanvasFrame = null, Action<string>? modeChanged = null,
    Func<PenMappingOptions>? mapping = null, PageLibrary? library = null)
  {
    long lastPing = Stopwatch.GetTimestamp();
    using var stop = cancellation.Register(session.Dispose);
    using var watchdog = new Timer(_ =>
    {
      if (Stopwatch.GetElapsedTime(Interlocked.Read(ref lastPing)) >= TimeSpan.FromSeconds(3))
        session.Dispose();
    }, null, 100, 100);
    ulong epoch = 0;
    string? activeMode = null;
    bool controlsVisibility = session.SupportsCanvasVisibility && library != null && replica != null;
    bool liveCanvas = true;
    using (session)
    {
      if (library != null)
        session.Send(FrameKind.PageReply, JsonSerializer.SerializeToUtf8Bytes(new { ready = true, strokeSync = session.SupportsStrokeSync ? library.EnableStrokeSync() : null, ink1 = true, ink2 = true, navigation = true, pc = Environment.MachineName }));
      while (!cancellation.IsCancellationRequested)
      {
        Frame? frame = null;
        CanvasReply? reply = null;
        Frame? acceptedCanvas = null;
        if (!session.Receive(item =>
        {
          if (item.Kind is FrameKind.PageList or FrameKind.PageChunk or FrameKind.PageCurrent)
          {
            if (library == null) throw new InvalidDataException("협상하지 않은 페이지 동기화");
            reply = library.Accept(item);
            return;
          }
          if (item.Kind is FrameKind.PenBatch or FrameKind.StrokePreview or FrameKind.CommitBegin
            or FrameKind.Chunk or FrameKind.CommitEnd)
          {
            if (item.Kind != FrameKind.Chunk)
            {
              using var data = JsonDocument.Parse(item.Payload);
              if (!ulong.TryParse(data.RootElement.GetProperty("epoch").GetString(), NumberStyles.None,
                  CultureInfo.InvariantCulture, out var frameEpoch) || frameEpoch > epoch)
                throw new InvalidDataException("데이터 epoch 오류");
              if (frameEpoch < epoch) return;
            }
            bool pen = item.Kind == FrameKind.PenBatch;
            if (activeMode != (pen ? "Digitizer" : "Canvas")) throw new InvalidDataException("데이터 모드 불일치");
            if (pen) digitizer?.Invoke(item);
            else if (item.Kind == FrameKind.StrokePreview && !liveCanvas) return;
            else if (replica != null) { reply = replica.Accept(item); acceptedCanvas = item; }
            else canvas?.Invoke(item);
          }
          else frame = item;
        })) return;
        if (reply != null) session.Send(reply.Kind, reply.Payload);
        if (library?.TakeRequest() is string canvasId)
          session.Send(FrameKind.PageReply,JsonSerializer.SerializeToUtf8Bytes(new { select = canvasId }));
        if (acceptedCanvas != null) afterCanvasFrame?.Invoke(acceptedCanvas);
        if (controlsVisibility && activeMode == "Canvas" && liveCanvas != replica!.ViewVisible)
        {
          liveCanvas = replica.ViewVisible;
          replica.ClearPreviews();
          session.Send(FrameKind.PageReply, JsonSerializer.SerializeToUtf8Bytes(new { epoch = epoch.ToString(CultureInfo.InvariantCulture), liveCanvas }));
        }
        if (frame == null) continue;
        if (frame.Kind == FrameKind.Close) return;
        using var document = JsonDocument.Parse(frame.Payload);
        var body = document.RootElement;
        switch (frame.Kind)
        {
          case FrameKind.Ping:
            if (activeMode == "Digitizer") checkTarget?.Invoke();
            var t2 = Micros();
            var nonce = body.GetProperty("nonce").GetString() ?? throw new InvalidDataException("잘못된 nonce");
            var t1 = body.GetProperty("t1").GetString();
            if (!ulong.TryParse(t1, NumberStyles.None, CultureInfo.InvariantCulture, out _))
              throw new InvalidDataException("잘못된 Ping 시각");
            Interlocked.Exchange(ref lastPing, Stopwatch.GetTimestamp());
            session.Send(FrameKind.Pong, JsonSerializer.SerializeToUtf8Bytes(new { nonce, t1, t2, t3 = Micros() }));
            break;
          case FrameKind.SetMode:
            var mode = body.GetProperty("mode").GetString();
            var epochText = body.GetProperty("modeEpoch").GetString();
            if (mode is not ("Digitizer" or "Canvas") || !session.Modes.Contains(mode) || !ulong.TryParse(epochText, NumberStyles.None,
                CultureInfo.InvariantCulture, out var next) || next <= epoch)
              throw new InvalidDataException("모드 또는 epoch 오류");
            if (activeMode == "Digitizer") endDigitizer?.Invoke();
            if (activeMode == "Canvas") replica?.Disconnect();
            if (mode == "Digitizer") checkTarget?.Invoke();
            epoch = next;
            activeMode = mode;
            liveCanvas = !controlsVisibility || mode != "Canvas" || replica!.ViewVisible;
            var ack = mode == "Digitizer" && target is ScreenRect screen
              ? JsonSerializer.SerializeToUtf8Bytes(new { mode, modeEpoch = epochText, width = screen.Width, height = screen.Height, mapping = mapping?.Invoke() })
              : controlsVisibility && mode == "Canvas"
                ? JsonSerializer.SerializeToUtf8Bytes(new { mode, modeEpoch = epochText, liveCanvas })
                : JsonSerializer.SerializeToUtf8Bytes(new { mode, modeEpoch = epochText });
            session.Send(FrameKind.ModeAck, ack);
            modeChanged?.Invoke(mode);
            if (mode == "Canvas" && replica != null)
            {
              var request = replica.Start(epochText!);
              session.Send(request.Kind, request.Payload);
            }
            break;
          default: throw new InvalidDataException("P1에서 처리하지 않는 프레임");
        }
      }
    }
  }

  private static string Micros() =>
    (Stopwatch.GetElapsedTime(0).Ticks / 10).ToString(CultureInfo.InvariantCulture);
}
