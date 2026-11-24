using System.Buffers.Binary;

namespace PenRelay.Core;

public enum FrameKind : byte
{
  Hello = 1, Welcome = 2, SetMode = 3, ModeAck = 4, Ping = 5, Pong = 6, Close = 7,
  PenBatch = 16, StrokePreview = 32, CommitBegin = 33, Chunk = 34, CommitEnd = 35,
  CanvasAck = 36, RequestSnapshot = 37, PageList = 40, PageChunk = 41, PageReply = 42, PageCurrent = 43
}

public sealed record Frame(FrameKind Kind, Guid SessionId, ulong Sequence, ulong SentUs, byte[] Payload);

internal readonly record struct FrameHeader(FrameKind Kind, Guid SessionId, ulong Sequence, ulong SentUs, int Length)
{
  internal Frame WithPayload(byte[] payload) => new(Kind, SessionId, Sequence, SentUs, payload);
}

public static class FrameCodec
{
  public const int HeaderSize = 44;
  public const int MaxPayload = 1024 * 1024;

  public static Frame? Read(Stream input) => Read(input, null);

  internal static Frame? Read(Stream input, Action<FrameHeader>? validate)
  {
    Span<byte> header = stackalloc byte[HeaderSize];
    int first = input.ReadByte();
    if (first < 0) return null;
    header[0] = (byte)first;
    input.ReadExactly(header[1..]);
    var fields = DecodeHeader(header);
    validate?.Invoke(fields);
    var payload = new byte[fields.Length];
    input.ReadExactly(payload);
    return fields.WithPayload(payload);
  }

  internal static async Task<Frame> ReadHandshakeAsync(Stream input, Action<FrameHeader> validate,
    CancellationToken cancellation)
  {
    var header = new byte[HeaderSize];
    await input.ReadExactlyAsync(header, cancellation);
    var fields = DecodeHeader(header);
    validate(fields);
    var payload = new byte[fields.Length];
    await input.ReadExactlyAsync(payload, cancellation);
    return fields.WithPayload(payload);
  }

  private static FrameHeader DecodeHeader(ReadOnlySpan<byte> header)
  {
    if (!header[..4].SequenceEqual("PRLY"u8)) throw new InvalidDataException("PRLY magic 불일치");
    if (header[4] != 1 || header[5] != 0) throw new InvalidDataException("지원하지 않는 프레임 버전");
    var kind = (FrameKind)header[6];
    if (!Enum.IsDefined(kind) || header[7] != 0) throw new InvalidDataException("알 수 없는 종류 또는 flags");
    uint length = BinaryPrimitives.ReadUInt32BigEndian(header[8..]);
    ValidateLength(kind, length);
    var id = new Guid(header.Slice(12, 16), bigEndian: true);
    ulong sequence = BinaryPrimitives.ReadUInt64BigEndian(header[28..]);
    ulong sentUs = BinaryPrimitives.ReadUInt64BigEndian(header[36..]);
    return new FrameHeader(kind, id, sequence, sentUs, (int)length);
  }

  public static void Write(Stream output, Frame frame)
  {
    if (!Enum.IsDefined(frame.Kind)) throw new InvalidDataException("알 수 없는 종류");
    ValidateLength(frame.Kind, (uint)frame.Payload.Length);
    Span<byte> header = stackalloc byte[HeaderSize];
    "PRLY"u8.CopyTo(header);
    header[4] = 1;
    header[5] = 0;
    header[6] = (byte)frame.Kind;
    header[7] = 0;
    BinaryPrimitives.WriteUInt32BigEndian(header[8..], (uint)frame.Payload.Length);
    frame.SessionId.TryWriteBytes(header[12..28], bigEndian: true, out _);
    BinaryPrimitives.WriteUInt64BigEndian(header[28..], frame.Sequence);
    BinaryPrimitives.WriteUInt64BigEndian(header[36..], frame.SentUs);
    output.Write(header);
    output.Write(frame.Payload);
  }

  private static void ValidateLength(FrameKind kind, uint length)
  {
    bool valid = kind == FrameKind.Chunk ? length is >= 21 and <= 20 + 256 * 1024
                                         : length is >= 2 and <= MaxPayload;
    if (!valid) throw new InvalidDataException($"잘못된 본문 길이 {length}");
  }
}
