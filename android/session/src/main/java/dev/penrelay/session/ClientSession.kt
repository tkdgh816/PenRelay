package dev.penrelay.session

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLSocket

class ClientSession private constructor(private val socket: SSLSocket, val id: UUID, val maxPayload: Int, val pc: String?) : Closeable {
    private val readGate = Any()
    private val writeGate = Any()
    private var lastReceived = 0uL
    private var lastSent = 0uL

    fun receive(): Frame? = synchronized(readGate) {
        try {
            val frame = FrameCodec.read(socket.getInputStream()) { kind, sessionId, sequence ->
                if (sessionId != id) throw IOException("세션 ID 불일치")
                if (lastReceived == ULong.MAX_VALUE || sequence != lastReceived + 1uL) throw IOException("수신 순번 불일치")
                if (kind == FrameKind.HELLO || kind == FrameKind.WELCOME) throw IOException("세션 협상 중복")
            }
            if (frame != null) lastReceived = frame.sequence
            if (frame == null || frame.kind == FrameKind.CLOSE) close()
            frame
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun send(kind: FrameKind, payload: ByteArray) = synchronized(writeGate) {
        try {
            if (kind == FrameKind.HELLO || kind == FrameKind.WELCOME) throw IOException("세션 협상 중복")
            if (lastSent == ULong.MAX_VALUE || payload.size > maxPayload) throw IOException("송신 한도 초과")
            val sequence = lastSent + 1uL
            FrameCodec.write(socket.getOutputStream(), Frame(kind, id, sequence, nowUs(), payload))
            lastSent = sequence
            if (kind == FrameKind.CLOSE) close()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() = socket.close()

    companion object {
        private val json = GsonBuilder().setStrictness(Strictness.STRICT).create()
        private val emptyId = UUID(0, 0)
        private val origin = System.nanoTime()
        private fun nowUs() = ((System.nanoTime() - origin) / 1000).toULong()

        internal fun negotiate(socket: SSLSocket, modes: List<String>, sensorCaps: List<String>): ClientSession {
            try {
                val hello = JsonObject().apply {
                    add("versions", json.toJsonTree(listOf(mapOf("major" to 1, "minor" to 0))))
                    add("modes", json.toJsonTree(modes))
                    add("sensorCaps", json.toJsonTree(sensorCaps))
                    if ("pages-v1" in sensorCaps) addProperty("deviceName", android.os.Build.MODEL)
                }
                FrameCodec.write(socket.getOutputStream(), Frame(FrameKind.HELLO, emptyId, 0uL, nowUs(),
                    json.toJson(hello).encodeToByteArray()))
                val welcome = FrameCodec.read(socket.getInputStream()) { kind, id, sequence ->
                    if (kind != FrameKind.WELCOME || id == emptyId || sequence != 0uL)
                        throw IOException("첫 응답은 새 세션 ID와 순번 0의 Welcome이어야 합니다.")
                } ?: throw EOFException("Welcome 수신 전 종료")
                val body = json.fromJson(welcome.payload.decodeToString(throwOnInvalidSequence = true), JsonObject::class.java)
                    ?: throw IOException("빈 Welcome")
                if (number(body, "major") != 1 || number(body, "minor") != 0) throw IOException("제안하지 않은 프로토콜 버전")
                val limits = body.get("limits")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("limits 누락")
                val max = number(limits, "maxPayload") ?: throw IOException("잘못된 maxPayload")
                if (max < 2) throw IOException("잘못된 maxPayload")
                val name = body.get("pc")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.filterNot { it.isISOControl() }?.take(64)?.takeIf { it.isNotBlank() }
                return ClientSession(socket, welcome.sessionId, minOf(max, FrameCodec.MAX_PAYLOAD), name)
            } catch (error: Exception) {
                socket.close()
                if (error is IOException) throw error
                throw IOException("Welcome 형식 오류", error)
            }
        }

        private fun number(obj: JsonObject, name: String): Int? = obj.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
    }
}
