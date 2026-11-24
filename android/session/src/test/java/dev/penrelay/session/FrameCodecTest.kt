package dev.penrelay.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class FrameCodecTest {
    private val rows = File(requireNotNull(System.getProperty("p1.vectors"))).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    @Test fun sharedVectorsInOneByteReads() {
        val wire = rows.map { it[6].hexBytes() }.reduce(ByteArray::plus)
        val input = object : ByteArrayInputStream(wire) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        for (row in rows) {
            val frame = requireNotNull(FrameCodec.read(input))
            val id = ByteBuffer.wrap(row[2].hexBytes())
            assertEquals(row[1].toInt(), frame.kind.code)
            assertEquals(UUID(id.long, id.long), frame.sessionId)
            assertEquals(row[3].toULong(), frame.sequence)
            assertEquals(row[4].toULong(), frame.sentUs)
            assertArrayEquals(row[5].hexBytes(), frame.payload)
            val encoded = ByteArrayOutputStream()
            FrameCodec.write(encoded, frame)
            assertArrayEquals(row[6].hexBytes(), encoded.toByteArray())
        }
        assertNull(FrameCodec.read(input))
    }

    @Test fun rejectsEveryTruncatedPrefix() {
        for (row in rows) {
            val bytes = row[6].hexBytes()
            for (count in 1 until bytes.size) {
                assertThrows(EOFException::class.java) { FrameCodec.read(ByteArrayInputStream(bytes, 0, count)) }
            }
        }
    }

    @Test fun rejectsBadHeadersBeforeReadingPayload() {
        val header = rows.first()[6].hexBytes().copyOf(FrameCodec.HEADER_SIZE)
        fun reject(change: (ByteArray) -> Unit) {
            val input = header.copyOf().also(change).inputStream()
            val error = assertThrows(IOException::class.java) { FrameCodec.read(input) }
            assertFalse(error is EOFException)
        }
        for (offset in listOf(0, 4, 5, 6, 7)) reject { it[offset] = 0x7f }
        for (length in listOf(0, 1, FrameCodec.MAX_PAYLOAD + 1, -1)) {
            reject { ByteBuffer.wrap(it).putInt(8, length) }
        }
        for (length in listOf(20, 20 + 256 * 1024 + 1)) {
            reject { it[6] = 34; ByteBuffer.wrap(it).putInt(8, length) }
        }
    }
}
