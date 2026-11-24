using System.Buffers.Binary;
using System.IO.Compression;
using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text.Json;

namespace PenRelay.Core;

public sealed record CanvasImage(Guid Document, long Revision, int Width, int Height, byte[] Rgba, string Hash)
{
  public long PublishedAt { get; } = System.Diagnostics.Stopwatch.GetTimestamp();
}
public sealed record PreviewLine(Guid Id, long Revision, InkStroke Style, int Strength, bool PressureSamples,
  ImmutableList<InkSample> Points)
{
  public ImmutableDictionary<int, EraseTile> Tiles { get; init; } = ImmutableDictionary<int, EraseTile>.Empty;
}
public sealed record EraseTile(int X, int Y, int Width, int Height, byte[] Bgra);
public sealed record CanvasState(CanvasImage? Image, PreviewLine[] Preview, long OverlayBase, long Generation);
public sealed record CanvasReply(FrameKind Kind, byte[] Payload);

/// <summary>인증된 세션이 단독으로 쓴다. 뷰는 검증을 마친 버퍼만 읽는다.</summary>
public sealed class CanvasReplica
{
  private readonly record struct PixelRect(int X, int Y, int Width, int Height);
  private sealed class Transfer
  {
    public required Guid Id, Doc;
    public required long Revision;
    public required int Width, Height;
    public required bool Full, Compressed;
    public required PixelRect[] Regions;
    public required string Hash;
    public required byte[] Payload;
    public required Guid[] StrokeIds;
    public long OverlayBase;
    public int Received;
  }
  private readonly object gate = new();
  private readonly Dictionary<Guid, PreviewLine> previews = [];
  private long overlayBase, visualBase;
  private Guid? stagingEraser, confirmedEraser;
  private long stagingRevision;
  private readonly Dictionary<int, EraseTile> stagedTiles = [];
  private long generation;
  public long Generation { get { lock (gate) return generation; } }
  private Transfer? incoming;
  private CanvasImage? image;
  private bool waitingFull;
  private (Guid Id, Guid Doc, CanvasReply Ack)? completed;
  private string epoch = "";
  public event Action? Changed;
  public event Action? ImageCommitted;
  public event Action<CanvasReplica, Guid, PreviewLine[]>? PreviewsCommitted;
  private volatile bool viewVisible = true;
  public bool ViewVisible { get => viewVisible; set => viewVisible = value; }
  public CanvasImage? CommittedImage { get { lock (gate) return image; } }

  public CanvasState Read()
  {
    lock (gate) return new(image, previews.Values.ToArray(), visualBase, generation);
  }

  internal void ClearPreviews()
  {
    lock (gate)
    {
      generation++;
      previews.Clear();
      ClearStagedTiles();
      visualBase = overlayBase;
      confirmedEraser = null;
    }
    Changed?.Invoke();
  }

  public void Disconnect()
  {
    incoming = null;
    completed = null;
    epoch = "";
    ClearPreviews();
  }
  public CanvasReply Start(string newEpoch)
  {
    Disconnect();
    epoch = newEpoch;
    return Request("새 Canvas 세션");
  }
  private CanvasReply Request(string reason)
  {
    incoming = null;
    waitingFull = true;
    completed = null;
    ClearPreviews();
    return new(FrameKind.RequestSnapshot, JsonSerializer.SerializeToUtf8Bytes(new { epoch, doc = image?.Document, reason, pixelDelta1 = true, inkPreview1 = true, savedStroke1 = true, eraserPreview1 = true }));
  }
  public CanvasReply? Accept(Frame frame)
  {
    if (frame.Kind == FrameKind.Chunk)
    {
      if (incoming == null) return waitingFull ? null : Request("시작 없는 조각");
      var bytes = frame.Payload.AsSpan();
      var t = incoming;
      if (bytes.Length is < 21 or > (20 + 256 * 1024) || new Guid(bytes[..16], true) != t.Id ||
        BinaryPrimitives.ReadUInt32BigEndian(bytes[16..20]) != t.Received ||
        bytes.Length - 20 > t.Payload.Length - t.Received) return Request("조각 순서 또는 길이 오류");
      var data = bytes[20..];
      data.CopyTo(t.Payload.AsSpan(t.Received));
      t.Received += data.Length;
      return null;
    }
    using var json = JsonDocument.Parse(frame.Payload);
    var body = json.RootElement;
    if (body.GetProperty("epoch").GetString() != epoch) return null;
    if (frame.Kind == FrameKind.StrokePreview) { Preview(body); return null; }
    if (frame.Kind == FrameKind.CommitBegin) return Begin(body);
    if (frame.Kind != FrameKind.CommitEnd) throw new InvalidDataException("알 수 없는 Canvas 프레임");
    if (incoming == null)
    {
      if (completed is { } last && last.Id == body.GetProperty("transferId").GetGuid() &&
        last.Doc == body.GetProperty("doc").GetGuid()) return last.Ack;
      return waitingFull ? null : Request("시작 없는 전송 종료");
    }
    var finished = incoming;
    incoming = null;
    if (body.GetProperty("transferId").GetGuid() != finished.Id || body.GetProperty("doc").GetGuid() != finished.Doc ||
      finished.Received != finished.Payload.Length) return Request("불완전한 전송");
    byte[] pixels;
    try { pixels = Restore(finished); }
    catch (InvalidDataException) { return Request("픽셀 데이터 형식 오류"); }
    catch (EndOfStreamException) { return Request("픽셀 데이터 길이 오류"); }
    string hash = PixelHash(finished.Width, finished.Height, pixels);
    if (finished.Hash != hash || (image?.Document == finished.Doc && image.Revision == finished.Revision && image.Hash != hash))
      return Request("확정 해시 불일치");
    PreviewLine[] committedLines;
    lock (gate)
    {
      if (image?.Document != finished.Doc)
      {
        previews.Clear();
        generation++;
        visualBase = overlayBase = 0;
        confirmedEraser = null;
        ClearStagedTiles();
      }
      Guid? erase = finished.Revision == finished.OverlayBase && finished.StrokeIds.Length == 1
        ? finished.StrokeIds[0] : null;
      AdvanceOverlayBase(finished.OverlayBase, erase);
      if (stagingEraser.HasValue && stagingRevision <= finished.Revision) ClearStagedTiles();
      committedLines = PreviewsCommitted == null ? [] : previews.Values
        .Where(line => line.Revision <= finished.Revision).ToArray();
      if (image?.Document != finished.Doc || image.Revision != finished.Revision)
        image = new(finished.Doc, finished.Revision, finished.Width, finished.Height, pixels, hash);
      foreach (var id in finished.StrokeIds)
        if (previews.TryGetValue(id, out var line) && line.Revision <= finished.Revision) previews.Remove(id);
      foreach (var id in previews.Where(p => p.Value.Revision <= finished.Revision).Select(p => p.Key).ToArray())
        previews.Remove(id);
    }
    waitingFull = false;
    if (committedLines.Length > 0) PreviewsCommitted?.Invoke(this, finished.Doc, committedLines);
    ImageCommitted?.Invoke();
    Changed?.Invoke();
    var ack = new CanvasReply(FrameKind.CanvasAck, JsonSerializer.SerializeToUtf8Bytes(new {
      epoch, doc = finished.Doc, revision = finished.Revision.ToString(), hash }));
    completed = (finished.Id, finished.Doc, ack);
    return ack;
  }

  private CanvasReply? Begin(JsonElement body)
  {
    var doc = body.GetProperty("doc").GetGuid();
    var id = body.GetProperty("transferId").GetGuid();
    var rect = body.GetProperty("rect");
    if (rect.GetArrayLength() != 4) return Request("사각형 형식 오류");
    int x = rect[0].GetInt32(), y = rect[1].GetInt32(), w = rect[2].GetInt32(), h = rect[3].GetInt32();
    int width = body.GetProperty("canvasW").GetInt32(), height = body.GetProperty("canvasH").GetInt32();
    int length = body.GetProperty("byteLength").GetInt32();
    string hash = body.GetProperty("fullHash").GetString() ?? "";
    bool full = body.GetProperty("full").GetBoolean();
    var ids = body.GetProperty("strokeIds");
    if (width is < 1 or > 4096 || height is < 1 or > 4096 || x < 0 || y < 0 || w <= 0 || h <= 0 ||
      (long)x+w > width || (long)y+h > height || length < 0 ||
      hash.Length != 64 || !hash.All(char.IsAsciiHexDigit) || ids.GetArrayLength() > 256 ||
      !long.TryParse(body.GetProperty("newRev").GetString(), out long rev) || rev < 0 ||
      !long.TryParse(body.GetProperty("baseRev").GetString(), out long basis) || basis < 0)
      return Request("전송 메타데이터 오류");
    if (incoming != null || (doc == image?.Document && rev < image.Revision) ||
      (full ? (w != width || h != height) : (waitingFull || image?.Document != doc ||
        image.Width != width || image.Height != height || image.Revision != basis || rev <= basis)))
      return Request("기준 revision 또는 크기 불일치");
    PixelRect[] regions = [new(x, y, w, h)];
    if (body.TryGetProperty("tiles", out var tiles))
    {
      if (full || w != width || h != height || !TryTiles(tiles, width, height, out regions))
        return Request("변경 타일 목록 오류");
    }
    bool compressed = body.TryGetProperty("encoding", out var encoding);
    int rawLength = regions.Sum(r => r.Width * r.Height * 4);
    if (compressed ? (encoding.ValueKind != JsonValueKind.String || encoding.GetString() != "zlib" ||
        length <= 0 || length >= rawLength) : length != rawLength)
      return Request("픽셀 인코딩 또는 길이 오류");
    var covered = ids.EnumerateArray().Select(s => s.GetGuid()).ToList();
    long basisForInk = 0;
    if (body.TryGetProperty("overlayBase", out var field) &&
      (!long.TryParse(field.GetString(), out basisForInk) || basisForInk < 0 || basisForInk > rev))
      return Request("획 표시 기준 버전 오류");
    incoming = new() { Id = id, Doc = doc, Revision = rev, Width = width, Height = height,
      Full = full, Compressed = compressed, Regions = regions, Hash = hash.ToLowerInvariant(),
      OverlayBase = basisForInk, StrokeIds = covered.ToArray(), Payload = new byte[length] };
    return null;
  }

  private static bool TryTiles(JsonElement tiles, int width, int height, out PixelRect[] regions)
  {
    const int tileSize = 32;
    int columns = (width + tileSize - 1) / tileSize;
    int count = columns * ((height + tileSize - 1) / tileSize);
    regions = [];
    if (tiles.ValueKind != JsonValueKind.Array || tiles.GetArrayLength() > count) return false;
    regions = new PixelRect[tiles.GetArrayLength()];
    int previous = -1, index = 0;
    foreach (var tile in tiles.EnumerateArray())
    {
      if (tile.ValueKind != JsonValueKind.Number || !tile.TryGetInt32(out int id) || id <= previous || id >= count)
        return false;
      previous = id;
      int x = id % columns * tileSize, y = id / columns * tileSize;
      regions[index++] = new(x, y, Math.Min(tileSize, width - x), Math.Min(tileSize, height - y));
    }
    return true;
  }

  private byte[] Restore(Transfer t)
  {
    if (t.Regions.Length == 0) return image!.Rgba;
    if (!t.Compressed && t.Full) return t.Payload;
    var pixels = t.Full ? new byte[t.Width * t.Height * 4] : (byte[])image!.Rgba.Clone();
    using var source = new MemoryStream(t.Payload, writable: false);
    using var inflater = t.Compressed ? new ZLibStream(source, CompressionMode.Decompress) : null;
    Stream input = (Stream?)inflater ?? source;
    foreach (var r in t.Regions)
      for (int y = r.Y; y < r.Y + r.Height; y++)
        input.ReadExactly(pixels.AsSpan((y * t.Width + r.X) * 4, r.Width * 4));
    if (input.ReadByte() != -1) throw new InvalidDataException("픽셀 영역 초과");
    return pixels;
  }

  // 호출자는 gate를 보유한다. 실행 취소·지우기 이전에 받았던 획을 다시 그리지 않는다.
  private void AdvanceOverlayBase(long revision, Guid? erase = null, long? previous = null)
  {
    if (revision <= overlayBase) return;
    long oldBase = overlayBase;
    overlayBase = revision;
    confirmedEraser = erase;
    if (stagingRevision <= revision && stagingEraser != erase) ClearStagedTiles();
    // 같은 바탕에서 완료된 지우기만 확정 화면이 준비될 때까지 이어 그린다.
    if (erase.HasValue && (previous == oldBase || (previous == null && previews.TryGetValue(erase.Value, out var line) &&
      line.Revision == revision && line.Style.Brush is 3 or 4))) return;
    visualBase = revision;
    foreach (var id in previews.Where(p => p.Value.Revision <= revision).Select(p => p.Key).ToArray())
      previews.Remove(id);
  }

  private void SavedOverlay(JsonElement body, CanvasImage canvas, bool hasStroke)
  {
    if (!long.TryParse(body.GetProperty("overlayBase").GetString(), out var basis) || basis < 0)
      throw new InvalidDataException("저장된 획 표시 기준 오류");
    PreviewLine? handoff = null;
    lock (gate)
    {
      Guid? editId = body.TryGetProperty("editId", out var edit) ? edit.GetGuid() : null;
      long? previous = body.TryGetProperty("previousBase", out var prior) &&
        long.TryParse(prior.GetString(), out var value) ? value : null;
      AdvanceOverlayBase(basis, editId, previous);
      if (hasStroke)
      {
        if (!long.TryParse(body.GetProperty("revision").GetString(), out var revision) || revision <= overlayBase) return;
        var id = body.GetProperty("strokeId").GetGuid();
        if (previews.ContainsKey(id) || previews.Count >= 32) return;
        var bytes = body.GetProperty("ink").GetBytesFromBase64();
        if (bytes.Length > 16 + 4096 * 12) throw new InvalidDataException("저장된 획 표본 한도 초과");
        var style = InkPage.DecodeStroke(bytes, new PageVersion(canvas.Document, revision, canvas.Width, canvas.Height), true)!;
        if (style.Brush == 3) throw new InvalidDataException("저장된 획 표시 도구 오류");
        var line = new PreviewLine(id, revision, style with { Points = [] }, 100, true, style.Points.ToImmutableList());
        if (revision > canvas.Revision) previews[id] = line;
        else handoff = line; // 픽셀을 먼저 받은 창도 확대 벡터를 기다리는 동안 표시한다.
      }
    }
    if (handoff != null) PreviewsCommitted?.Invoke(this, canvas.Document, [handoff]);
    Changed?.Invoke();
  }

  private void Preview(JsonElement body)
  {
    if (waitingFull || image == null || body.GetProperty("doc").GetGuid() != image.Document) return;
    string? action = body.GetProperty("action").GetString();
    if (action is "saved" or "invalidateSaved")
    {
      SavedOverlay(body, image, action == "saved");
      return;
    }
    Guid id = body.GetProperty("strokeId").GetGuid();
    if (action is "eraseTile" or "eraseTilesReady")
    {
      WholeErase(body, image, id, action == "eraseTilesReady");
      return;
    }
    lock (gate)
    {
      if (action == "cancel")
      {
        if (stagingEraser == id) ClearStagedTiles();
        previews.Remove(id);
      }
      else
      {
        if (!long.TryParse(body.GetProperty("revision").GetString(), out long rev) || rev <= Math.Max(image.Revision, overlayBase)) return;
        var points = body.GetProperty("points");
        if (points.GetArrayLength() is < 1 or > 256) return;
        PreviewLine? line;
        if (action == "begin")
        {
          float width = body.GetProperty("width").GetSingle();
          var rgb = body.GetProperty("color").GetString();
          if (previews.Count >= 32 || !float.IsFinite(width) || width is <= 0 or > 32768 || rgb?.Length != 6 ||
            !uint.TryParse(rgb, System.Globalization.NumberStyles.HexNumber, null, out var color)) return;
          bool rich = body.TryGetProperty("brush", out var brushValue);
          byte brush = rich ? brushValue.GetByte() : (byte)0;
          var texture = rich ? (InkTexture)body.GetProperty("texture").GetByte() : InkTexture.Smooth;
          int strength = rich ? body.GetProperty("strength").GetInt32() : 100;
          if (brush > 3 || !Enum.IsDefined(texture) || strength is < 0 or > 100 ||
            (brush == 3 && (texture != InkTexture.Smooth || body.GetProperty("pressure").GetBoolean())))
            throw new InvalidDataException("미리보기 브러시 속성 오류");
          var style = new InkStroke(brush, color, width, rich && body.GetProperty("pressure").GetBoolean(), [], texture);
          line = new(id, rev, style, strength, rich, ImmutableList<InkSample>.Empty);
        }
        else if (action != "append" || !previews.TryGetValue(id, out line) || rev != line.Revision) return;

        if (line.PressureSamples && body.GetProperty("offset").GetInt32() != line.Points.Count) return;
        // 단일 임시 획과 세션 전체의 메모리 사용량을 제한한다.
        if (previews.Values.Sum(p => p.Points.Count) + points.GetArrayLength() > 1_048_576) return;
        var samples = new InkSample[points.GetArrayLength()];
        int i = 0;
        foreach (var point in points.EnumerateArray())
        {
          if (point.GetArrayLength() != (line.PressureSamples ? 3 : 2)) throw new InvalidDataException("미리보기 좌표 오류");
          float x = point[0].GetSingle(), y = point[1].GetSingle();
          if (!float.IsFinite(x) || !float.IsFinite(y) || x < 0 || x >= image.Width || y < 0 || y >= image.Height)
            throw new InvalidDataException("문서 범위를 벗어난 미리보기");
          int pressure = line.PressureSamples ? point[2].GetInt32() : 0;
          if (pressure is < 0 or > 4095) throw new InvalidDataException("미리보기 필압 범위 오류");
          samples[i++] = new(new(x, y), InkStroke.EffectivePressure(pressure / 4095f, line.Strength));
        }
        previews[id] = line with { Points = line.Points.AddRange(samples) };
      }
    }
    Changed?.Invoke();
  }

  private void ClearStagedTiles()
  {
    stagingEraser = null;
    stagedTiles.Clear();
  }

  private void WholeErase(JsonElement body, CanvasImage canvas, Guid id, bool publish)
  {
    if (!long.TryParse(body.GetProperty("revision").GetString(), out var revision)) return;
    lock (gate)
    {
      if (revision <= canvas.Revision || revision < overlayBase ||
        (revision == overlayBase && id != confirmedEraser)) return;
      if (!publish)
      {
        const int size = 64;
        int x = body.GetProperty("x").GetInt32(), y = body.GetProperty("y").GetInt32();
        if (x < 0 || y < 0 || x >= canvas.Width || y >= canvas.Height || x % size != 0 || y % size != 0)
          throw new InvalidDataException("지우개 미리보기 위치 오류");
        if (stagingEraser != id)
        {
          ClearStagedTiles();
          stagingEraser = id;
          stagingRevision = revision;
        }
        int w = Math.Min(size, canvas.Width - x), h = Math.Min(size, canvas.Height - y);
        var compressed = body.GetProperty("pixels").GetBytesFromBase64();
        if (compressed.Length > size * size * 4 + 128) throw new InvalidDataException("지우개 조각 크기 오류");
        using var input = new MemoryStream(compressed, false);
        using var inflater = new ZLibStream(input, CompressionMode.Decompress);
        var bgra = new byte[w * h * 4];
        inflater.ReadExactly(bgra);
        if (inflater.ReadByte() != -1) throw new InvalidDataException("지우개 조각 길이 오류");
        stagedTiles[y / size * ((canvas.Width + size - 1) / size) + x / size] = new(x, y, w, h, bgra);
        return;
      }
      if (stagingEraser != id || stagingRevision != revision || stagedTiles.Count == 0) return;
      if (!previews.TryGetValue(id, out var line))
      {
        if (previews.Count >= 32) { ClearStagedTiles(); return; }
        line = new(id, revision, new(4, 0, 1, false, []), 100, false, ImmutableList<InkSample>.Empty);
      }
      if (line.Revision != revision || line.Style.Brush != 4) throw new InvalidDataException("지우개 묶음 불일치");
      var tiles = line.Tiles.SetItems(stagedTiles);
      ClearStagedTiles();
      if (tiles.Count + previews.Values.Where(p => p.Id != id).Sum(p => p.Tiles.Count) > 4096) return;
      previews[id] = line with { Tiles = tiles };
    }
    Changed?.Invoke();
  }

  public static string PixelHash(int width, int height, byte[] rgba)
  {
    Span<byte> size = stackalloc byte[8];
    BinaryPrimitives.WriteInt32BigEndian(size, width);
    BinaryPrimitives.WriteInt32BigEndian(size[4..], height);
    using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
    hash.AppendData(size);
    hash.AppendData(rgba);
    return Convert.ToHexStringLower(hash.GetHashAndReset());
  }
}

