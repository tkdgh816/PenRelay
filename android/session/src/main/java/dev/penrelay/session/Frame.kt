package dev.penrelay.session

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

enum class FrameKind(val code: Int) {
    HELLO(1), WELCOME(2), SET_MODE(3), MODE_ACK(4), PING(5), PONG(6), CLOSE(7),
    PEN_BATCH(16), STROKE_PREVIEW(32), COMMIT_BEGIN(33), CHUNK(34), COMMIT_END(35),
    CANVAS_ACK(36), REQUEST_SNAPSHOT(37), PAGE_LIST(40), PAGE_CHUNK(41), PAGE_REPLY(42), PAGE_CURRENT(43);
}

class Frame(
    val kind: FrameKind,
    val sessionId: UUID,
    val sequence: ULong,
    val sentUs: ULong,
    val payload: ByteArray
)

object FrameCodec {
    const val HEADER_SIZE = 44
    const val MAX_PAYLOAD = 1024 * 1024
    private const val MAGIC = 0x50524c59

    // 프레임 경계의 EOF만 정상 종료다. 부분 헤더와 부분 본문은 readFully가 거부한다.
    fun read(input: InputStream): Frame? = read(input) { _, _, _ -> }

    internal fun read(input: InputStream, validate: (FrameKind, UUID, ULong) -> Unit): Frame? {
        val first = input.read()
        if (first == -1) return null
        val header = ByteArray(HEADER_SIZE)
        header[0] = first.toByte()
        val stream = DataInputStream(input)
        stream.readFully(header, 1, HEADER_SIZE - 1)
        val bytes = ByteBuffer.wrap(header)
        if (bytes.int != MAGIC) throw IOException("PRLY magic 불일치")
        if (bytes.get().toInt() != 1 || bytes.get().toInt() != 0) throw IOException("지원하지 않는 프레임 버전")
        val code = bytes.get().toInt() and 255
        val kind = FrameKind.entries.find { it.code == code } ?: throw IOException("알 수 없는 종류 $code")
        if (bytes.get().toInt() != 0) throw IOException("알 수 없는 flags")
        val length = bytes.int
        validateLength(kind, length)
        val id = UUID(bytes.long, bytes.long)
        val sequence = bytes.long.toULong()
        val sentUs = bytes.long.toULong()
        validate(kind, id, sequence)
        val payload = ByteArray(length)
        stream.readFully(payload)
        return Frame(kind, id, sequence, sentUs, payload)
    }

    fun write(output: OutputStream, frame: Frame) {
        validateLength(frame.kind, frame.payload.size)
        val header = ByteBuffer.allocate(HEADER_SIZE)
        header.putInt(MAGIC).put(1).put(0).put(frame.kind.code.toByte()).put(0)
        header.putInt(frame.payload.size)
        header.putLong(frame.sessionId.mostSignificantBits).putLong(frame.sessionId.leastSignificantBits)
        header.putLong(frame.sequence.toLong()).putLong(frame.sentUs.toLong())
        output.write(header.array())
        output.write(frame.payload)
    }

    private fun validateLength(kind: FrameKind, length: Int) {
        // JSON 객체의 최소 길이는 2, Chunk는 식별자와 offset 뒤에 데이터를 담는다.
        val range = if (kind == FrameKind.CHUNK) 21..(20 + 256 * 1024) else 2..MAX_PAYLOAD
        if (length !in range) throw IOException("잘못된 본문 길이 $length")
    }
}
