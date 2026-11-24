using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json;
using PenRelay.Core;

namespace PenRelay.SessionFixture;

internal static class CanvasChecks
{
  internal static void Run()
  {
    var replica = new CanvasReplica();
    var doc = Guid.NewGuid();
    var stroke = Guid.NewGuid();
    var transfer = Guid.NewGuid();
    const string epoch = "1";
    static void Expect(bool value, string label)
    {
      if (!value) throw new InvalidDataException(label);
    }
    CanvasReply? Feed(FrameKind kind, byte[] bytes) => replica.Accept(new(kind, Guid.Empty, 0, 0, bytes));
    CanvasReply? Json(FrameKind kind, object value) => Feed(kind, JsonSerializer.SerializeToUtf8Bytes(value));
    var pixels = new byte[16*16*4];
    // 모서리 표식에는 투명·반투명·불투명을 섞는다.
    pixels[0] = 255; pixels[3] = 255;
    pixels[62] = 255; pixels[63] = 128;
    pixels[^64] = 64; pixels[^61] = 32;
    pixels[^3] = 255; pixels[^1] = 255;
    string Hash() => Convert.ToHexStringLower(SHA256.HashData(new byte[] {0,0,0,16,0,0,0,16}.Concat(pixels).ToArray()));
    CanvasReply? Begin(long rev, bool full, int[] rect, long basis, string? hash = null)
    {
      transfer = Guid.NewGuid();
      return Json(FrameKind.CommitBegin, new { epoch, doc, transferId = transfer, full, canvasW = 16, canvasH = 16,
        rect, byteLength = rect[2]*rect[3]*4, baseRev = basis.ToString(), newRev = rev.ToString(),
        fullHash = hash ?? Hash(), strokeIds = new[] { stroke } });
    }
    CanvasReply? Chunk(int offset, ReadOnlySpan<byte> bytes)
    {
      var chunk = new byte[20 + bytes.Length];
      transfer.TryWriteBytes(chunk, true, out _);
      BinaryPrimitives.WriteInt32BigEndian(chunk.AsSpan(16), offset);
      bytes.CopyTo(chunk.AsSpan(20));
      return Feed(FrameKind.Chunk, chunk);
    }
    CanvasReply? End() => Json(FrameKind.CommitEnd, new { epoch, doc, transferId = transfer });
    void Full(long rev)
    {
      Expect(Begin(rev, true, [0,0,16,16], 0) == null, "full 시작");
      Chunk(0, pixels.AsSpan(0, 7)); Chunk(7, pixels.AsSpan(7));
      Expect(End()?.Kind == FrameKind.CanvasAck && replica.Read().Image!.Rgba.SequenceEqual(pixels), "full 픽셀");
    }
    void Preview(string action, long rev) => Json(FrameKind.StrokePreview, new {
      epoch, doc, strokeId = stroke, action, revision = rev.ToString(), points = new[]{ new[]{1,2} }, width = 5, color = "245fa2" });
    replica.Start(epoch);
    Full(0);
    var first = replica.Read().Image;
    Expect(End()?.Kind == FrameKind.CanvasAck && ReferenceEquals(first, replica.Read().Image), "중복 End");
    Preview("begin", 1); Preview("append", 1);
    Expect(replica.Read().Preview.Single().Points.Count == 2, "미리보기");
    Preview("cancel", 1);
    Expect(replica.Read().Preview.Length == 0 && replica.Read().Image == first, "취소");
    for (int revision = 1; revision <= 2; revision++)
    {
      Preview("begin", revision);
      int x = revision, y = 9;
      byte[] delta = [255,0,0,128, 0,0,0,0, 0,255,0,64, 0,0,255,255];
      for (int row = 0; row < 2; row++) delta.AsSpan(row*8,8).CopyTo(pixels.AsSpan(((y+row)*16+x)*4));
      var old = replica.Read().Image;
      Begin(revision, false, [x,y,2,2], revision-1);
      Chunk(0, delta.AsSpan(0,5));
      Expect(replica.Read().Image == old && replica.Read().Preview.Length == 1, "부분 게시");
      Chunk(5, delta.AsSpan(5));
      Expect(End()?.Kind == FrameKind.CanvasAck && replica.Read().Image!.Rgba.SequenceEqual(pixels) &&
        replica.Read().Preview.Length == 0, "델타 및 미리보기 원자 교체");
    }
    var last = replica.Read().Image;
    Expect(Begin(3, false, [0,0,1,1], 1)?.Kind == FrameKind.RequestSnapshot, "잘못된 base");
    Begin(3, true, [0,0,16,16], 0); Chunk(0, pixels.AsSpan(0,8));
    Expect(Chunk(0, pixels.AsSpan(0,8))?.Kind == FrameKind.RequestSnapshot, "중복 조각");
    Begin(3, true, [0,0,16,16], 0); Chunk(0, pixels.AsSpan(0,8));
    Expect(End()?.Kind == FrameKind.RequestSnapshot, "누락");
    Begin(3, true, [0,0,16,16], 0, new string('0',64)); Chunk(0, pixels);
    Expect(End()?.Kind == FrameKind.RequestSnapshot, "해시 불일치");
    Begin(3, true, [0,0,16,16], 0); Chunk(0, pixels.AsSpan(0,8)); replica.Disconnect();
    Expect(replica.Read().Image == last && replica.Read().Preview.Length == 0, "중단 후 확정 화면");
    replica.Start(epoch); Full(3);
    Console.WriteLine("Canvas 16×16 full·델타 2회, 취소·중복·누락·해시·재접속 통과");
    CheckTitleOnlySync();
  }

  private static void CheckTitleOnlySync()
  {
    var dir = Directory.CreateTempSubdirectory("penrelay-title-");
    try
    {
      var id = Guid.NewGuid();
      var version = new PageVersion(id,3);
      var old = new { SyncedAt = DateTimeOffset.UtcNow, Pages = new[] { version } };
      File.WriteAllBytes(Path.Combine(dir.FullName,"pages.json"),JsonSerializer.SerializeToUtf8Bytes(old));
      var png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aTqsAAAAASUVORK5CYII=");
      File.WriteAllBytes(PageLibrary.ImagePath(dir.FullName,version),png);
      using var library = new PageLibrary(dir.FullName,_ => {});
      var batch = Guid.NewGuid();
      var list = new { batch, pages = new[] { new object[] { id,3 } }, titles = new Dictionary<Guid,string> { [id] = "수업 필기" } };
      var reply = library.Accept(new(FrameKind.PageList,Guid.Empty,0,0,JsonSerializer.SerializeToUtf8Bytes(list)));
      using var body = JsonDocument.Parse(reply!.Payload);
      if (!body.RootElement.GetProperty("complete").GetBoolean() ||
        PageLibrary.Read(dir.FullName)?.Titles?[id] != "수업 필기")
        throw new InvalidDataException("제목 변경 시 기존 파일 재사용 또는 제목 복원 실패");
      Console.WriteLine("구형 목록에서 페이지 재전송 없이 제목 동기화 및 재열기 통과");
    }
    finally { dir.Delete(true); }
  }
}

