using System.Buffers.Binary;
using PenRelay.Core;

namespace PenRelay.SessionFixture;

internal static class VectorChecks
{
  public static void Run(string path)
  {
    var rows = File.ReadLines(path).Where(line => line.Length > 0 && !line.StartsWith('#'))
                   .Select(line => line.Split('\t')).ToArray();
    var wires = rows.Select(row => Convert.FromHexString(row[6])).ToArray();
    using var input = new SingleByteReads(wires.SelectMany(bytes => bytes).ToArray());
    foreach (var row in rows)
    {
      var frame = FrameCodec.Read(input) ?? throw new EndOfStreamException();
      byte[] wire = Convert.FromHexString(row[6]);
      Check((byte)frame.Kind == byte.Parse(row[1]) && frame.SessionId == Guid.ParseExact(row[2], "N")
        && frame.Sequence == ulong.Parse(row[3]) && frame.SentUs == ulong.Parse(row[4])
        && frame.Payload.SequenceEqual(Convert.FromHexString(row[5])), row[0]);
      using var output = new MemoryStream();
      FrameCodec.Write(output, frame);
      Check(output.ToArray().SequenceEqual(wire), row[0]);
      for (int count = 1; count < wire.Length; count++)
      {
        using var truncated = new MemoryStream(wire, 0, count);
        try { FrameCodec.Read(truncated); }
        catch (EndOfStreamException) { continue; }
        throw new InvalidOperationException("잘린 프레임 수락");
      }
    }
    Check(FrameCodec.Read(input) == null, "정상 EOF");
    var header = wires[0][..FrameCodec.HeaderSize];
    void Reject(Action<byte[]> change)
    {
      var bad = header.ToArray();
      change(bad);
      using var stream = new MemoryStream(bad);
      try { FrameCodec.Read(stream); }
      catch (InvalidDataException) { return; }
      throw new InvalidOperationException("잘못된 헤더 수락");
    }
    foreach (int offset in new[] { 0, 4, 5, 6, 7 }) Reject(b => b[offset] = 0x7f);
    foreach (uint length in new uint[] { 0, 1, FrameCodec.MaxPayload + 1, uint.MaxValue })
      Reject(b => BinaryPrimitives.WriteUInt32BigEndian(b.AsSpan(8), length));
    foreach (uint length in new uint[] { 20, 20 + 256 * 1024 + 1 })
      Reject(b => { b[6] = 34; BinaryPrimitives.WriteUInt32BigEndian(b.AsSpan(8), length); });
    Console.WriteLine($"고정 벡터 {rows.Length}개, 부분 읽기·연속 프레임·잘린 EOF·헤더 거부 통과");
  }

  private static void Check(bool condition, string label)
  {
    if (!condition) throw new InvalidOperationException(label);
  }

  private sealed class SingleByteReads(byte[] bytes) : MemoryStream(bytes)
  {
    public override int Read(Span<byte> buffer) => base.Read(buffer[..Math.Min(buffer.Length, 1)]);
  }
}
