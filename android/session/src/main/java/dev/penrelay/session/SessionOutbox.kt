package dev.penrelay.session

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class OfferResult { ACCEPTED, BUSY, PREVIEW_DROPPED, CONTACT_SKIPPED, MODE_NOT_READY, CLOSED }
enum class TransportMode { Digitizer, Canvas }
data class QueueStats(val depth: Int, val bytes: Int, val droppedPreviews: Long)
internal class Outgoing(val kind: FrameKind, val payload: ByteArray, val queuedNanos: Long,
    val onPong: (() -> Unit)? = null) {
    val size = FrameCodec.HEADER_SIZE + payload.size
}

// 연결마다 새로 만들며 이전 연결의 생산자는 이 핸들로 새 큐에 접근할 수 없다.
class SessionOutbox internal constructor(val id: UUID, private val maxPayload: Int,
    private val slots: Int = 64, private val byteLimit: Int = FrameCodec.MAX_PAYLOAD + FrameCodec.HEADER_SIZE) {
    private val gate = Object()
    private val frames = LinkedList<Outgoing>()
    // 시험에서만 사용한다. 콜백은 writer 스레드에서 실행하며 I/O를 하지 않는다.
    @Volatile var onDequeued: ((waitMicros: Long, bytes: Int, depth: Int) -> Unit)? = null
    private var bytes = 0
    private var dropped = 0L
    private var closed = false
    private var waitingForDown = true
    private var pending: TransportMode? = null
    private var mode: TransportMode? = null
    private var epoch = 0uL

    fun selectMode(next: TransportMode): OfferResult = synchronized(gate) {
        if (closed) return OfferResult.CLOSED
        if (pending != null) return OfferResult.MODE_NOT_READY
        check(epoch != ULong.MAX_VALUE)
        val nextEpoch = epoch + 1uL
        val body = """{"mode":"${next.name}","modeEpoch":"$nextEpoch"}""".encodeToByteArray()
        val result = enqueue(FrameKind.SET_MODE, body)
        if (result == OfferResult.ACCEPTED) { pending = next; epoch = nextEpoch }
        result
    }

    internal fun acknowledge(payload: ByteArray) = synchronized(gate) {
        try {
            val body = JsonParser.parseString(payload.decodeToString()).asJsonObject
            if (pending == null || body["mode"].asString != pending?.name || body["modeEpoch"].asString != epoch.toString())
                throw java.io.IOException("요청과 다른 ModeAck")
        } catch (error: RuntimeException) { throw java.io.IOException("ModeAck 형식 오류", error) }
        mode = pending
        pending = null
        waitingForDown = true
    }

    fun offer(kind: FrameKind, payload: ByteArray): OfferResult = offer(kind, payload, false)

    fun offerOverlay(payload: ByteArray): OfferResult {
        if (payload.size > maxPayload) return OfferResult.PREVIEW_DROPPED
        return offer(FrameKind.STROKE_PREVIEW, payload, true)
    }

    private fun offer(kind: FrameKind, payload: ByteArray, aheadOfBulk: Boolean): OfferResult = synchronized(gate) {
        if (closed) return OfferResult.CLOSED
        require(payload.size in 2..maxPayload)
        val required = when (kind) {
            FrameKind.PAGE_CHUNK -> {
                if (frames.any { it.kind == FrameKind.PAGE_CHUNK } || frames.size >= slots / 2)
                    return OfferResult.BUSY
                return enqueue(kind, payload)
            }
            FrameKind.CLOSE, FrameKind.PAGE_LIST, FrameKind.PAGE_CURRENT -> return enqueue(kind, payload)
            FrameKind.PEN_BATCH -> TransportMode.Digitizer
            FrameKind.STROKE_PREVIEW, FrameKind.COMMIT_BEGIN, FrameKind.CHUNK, FrameKind.COMMIT_END -> TransportMode.Canvas
            else -> throw IllegalArgumentException("일반 송신 큐에서 지원하지 않는 종류")
        }
        if (pending != null || mode != required) return OfferResult.MODE_NOT_READY
        if (kind == FrameKind.CHUNK) {
            require(payload.size in 21..(20 + 256 * 1024))
            return enqueue(kind, payload)
        }
        val body = JsonParser.parseString(payload.decodeToString()).asJsonObject
        if (body["epoch"]?.asString != epoch.toString()) return OfferResult.MODE_NOT_READY
        var data = payload
        var startsContact = false
        if (kind == FrameKind.PEN_BATCH && waitingForDown) {
            val samples = body.getAsJsonArray("samples")
            require(samples.size() in 1..128)
            val kept = JsonArray()
            for (sample in samples) {
                val action = sample.asJsonObject["action"].asString
                if (action == "down") startsContact = true
                if (startsContact || action == "enter" || action == "hover" || action == "leave") kept.add(sample)
            }
            if (kept.isEmpty) return OfferResult.CONTACT_SKIPPED
            if (kept.size() != samples.size()) {
                body.add("samples", kept)
                data = body.toString().encodeToByteArray()
            }
        }
        val result = enqueue(kind, data, aheadOfBulk = aheadOfBulk)
        if (result == OfferResult.ACCEPTED && startsContact) waitingForDown = false
        result
    }

    // 앞서 넣은 데이터가 PC 처리기를 지난 시점을 Pong으로 확인한다.
    fun confirmDelivery(onPong: () -> Unit): OfferResult = synchronized(gate) {
        if (closed) return OfferResult.CLOSED
        // writer가 송신 순서대로 nonce와 t1을 만들 때 필요한 최대 공간을 예약한다.
        enqueue(FrameKind.PING, ByteArray(96), onPong)
    }

    private fun enqueue(kind: FrameKind, data: ByteArray, onPong: (() -> Unit)? = null, aheadOfBulk: Boolean = false): OfferResult {
        if (frames.size >= slots || data.size + FrameCodec.HEADER_SIZE > byteLimit - bytes) {
            if (kind != FrameKind.STROKE_PREVIEW) return OfferResult.BUSY
            dropped++
            return OfferResult.PREVIEW_DROPPED
        }
        val frame = Outgoing(kind, data.copyOf(), if (onDequeued == null) 0L else System.nanoTime(), onPong)
        if (aheadOfBulk) {
            // 픽셀과 보관함 묶음만 앞지른다. 모드 전환·Pong 경계·다른 획의 순서는 보존한다.
            val cursor = frames.listIterator(frames.size)
            while (cursor.hasPrevious()) {
                when (cursor.previous().kind) {
                    FrameKind.CHUNK, FrameKind.COMMIT_BEGIN, FrameKind.COMMIT_END,
                    FrameKind.PAGE_LIST, FrameKind.PAGE_CHUNK -> continue
                    else -> { cursor.next(); break }
                }
            }
            cursor.add(frame)
        } else frames.addLast(frame)
        bytes += frame.size
        gate.notifyAll()
        return OfferResult.ACCEPTED
    }

    internal fun poll(waitNanos: Long): Outgoing? = synchronized(gate) {
        if (!closed && frames.isEmpty() && waitNanos > 0) TimeUnit.NANOSECONDS.timedWait(gate, waitNanos)
        frames.pollFirst()?.also {
            bytes -= it.size
            gate.notifyAll()
            if (it.queuedNanos != 0L) onDequeued?.invoke((System.nanoTime() - it.queuedNanos) / 1000, it.size, frames.size + 1)
        }
    }

    fun stats(): QueueStats = synchronized(gate) { QueueStats(frames.size, bytes, dropped) }

    // 확정본을 보내는 작업 스레드만 대기한다. 입력과 미리보기는 비차단 offer를 사용한다.
    fun sendCanvasData(kind: FrameKind, payload: ByteArray): Boolean = synchronized(gate) {
        require(kind == FrameKind.STROKE_PREVIEW || kind == FrameKind.PAGE_LIST ||
            kind == FrameKind.PAGE_CHUNK || kind == FrameKind.PAGE_CURRENT || kind == FrameKind.COMMIT_BEGIN || kind == FrameKind.CHUNK || kind == FrameKind.COMMIT_END)
        var result = offer(kind,payload)
        while (result == OfferResult.BUSY || result == OfferResult.PREVIEW_DROPPED) {
            gate.wait()
            result = offer(kind,payload)
        }
        result == OfferResult.ACCEPTED
    }

    internal fun close() = synchronized(gate) {
        closed = true
        frames.clear()
        bytes = 0
        gate.notifyAll()
    }
}
