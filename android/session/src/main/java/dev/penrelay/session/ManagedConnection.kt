package dev.penrelay.session

import com.google.gson.JsonParser
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.random.Random

sealed interface ConnectionEvent {
    data class Online(val outbox: SessionOutbox, val pc: String? = null) : ConnectionEvent
    data class RoundTrip(val micros: Long, val queue: QueueStats) : ConnectionEvent
    data class Retry(val delayMillis: Long, val reason: String) : ConnectionEvent
    data object PermissionRequired : ConnectionEvent
    data object PairingRequired : ConnectionEvent
}

internal fun retryDelay(attempt: Int, jitter: Double = Random.nextDouble()): Long {
    val base = 500L shl attempt.coerceIn(0, 4)
    val low = base.coerceAtMost(6000L)
    val high = (base * 2).coerceAtMost(8000L)
    return low + ((high - low) * jitter).toLong()
}

// run은 전용 작업 스레드에서 호출한다. close도 TLS 종료 레코드를 쓸 수 있으므로 UI 밖에서 실행한다.
class ManagedConnection(
    private val permitted: () -> Boolean,
    private val connect: () -> ClientSession,
    private val onEvent: (ConnectionEvent) -> Unit,
    private val onFrame: (Frame) -> Unit = {}
) : Closeable {
    private val stopped = AtomicBoolean()
    private val owner = AtomicReference<Thread?>()
    private val active = AtomicReference<ClientSession?>()

    fun run() {
        check(owner.compareAndSet(null, Thread.currentThread()))
        var attempt = 0
        try {
            while (!stopped.get()) {
                if (!permitted()) { onEvent(ConnectionEvent.PermissionRequired); return }
                try {
                    connect().use { session ->
                        active.set(session)
                        if (stopped.get()) return
                        pump(session) { attempt = 0 }
                    }
                } catch (error: IOException) {
                    if (stopped.get()) return
                    if (!permitted()) { onEvent(ConnectionEvent.PermissionRequired); return }
                    if (error is PairingRequiredException || error is SSLPeerUnverifiedException ||
                        generateSequence<Throwable>(error) { it.cause }.any { it is java.security.cert.CertificateException } ||
                        (error is SSLException && error.message.orEmpty().contains("certificate", ignoreCase = true))) {
                        onEvent(ConnectionEvent.PairingRequired)
                        return
                    }
                    val delay = retryDelay(attempt)
                    attempt = (attempt + 1).coerceAtMost(4)
                    onEvent(ConnectionEvent.Retry(delay, error.message ?: "연결 종료"))
                    Thread.sleep(delay)
                } catch (_: SecurityException) {
                    onEvent(ConnectionEvent.PermissionRequired)
                    return
                } finally { active.set(null) }
            }
        } catch (interrupted: InterruptedException) {
            if (!stopped.get()) throw interrupted
        } finally { owner.set(null) }
    }

    private fun pump(session: ClientSession, healthy: () -> Unit) {
        val outbox = SessionOutbox(session.id, session.maxPayload)
        val alive = AtomicBoolean(true)
        val failure = AtomicReference<IOException?>()
        val heartbeat = Heartbeat()
        fun abort(reason: IOException) {
            if (alive.compareAndSet(true, false)) {
                failure.set(reason)
                outbox.close()
                session.close()
            }
        }
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleAtFixedRate({
            if (heartbeat.expired()) abort(IOException("Pong 3초 무응답"))
        }, 100, 100, TimeUnit.MILLISECONDS)
        val writer = thread(name = "penrelay-writer") {
            try {
                var next = System.nanoTime()
                while (alive.get()) {
                    if (System.nanoTime() >= next) {
                        session.send(FrameKind.PING, heartbeat.ping())
                        next = System.nanoTime() + 1_000_000_000L
                    }
                    outbox.poll(next - System.nanoTime())?.let {
                        val payload = if (it.onPong == null) it.payload else heartbeat.ping(it.onPong)
                        session.send(it.kind, payload)
                    }
                }
            } catch (error: Exception) {
                abort(IOException("송신 종료", error))
            }
        }
        try {
            onEvent(ConnectionEvent.Online(outbox, session.pc))
            while (!stopped.get()) {
                val frame = session.receive() ?: throw EOFException("PC 연결 종료")
                when (frame.kind) {
                    FrameKind.PONG -> {
                        val rtt = heartbeat.pong(frame)
                        healthy()
                        onEvent(ConnectionEvent.RoundTrip(rtt, outbox.stats()))
                    }
                    FrameKind.PING -> session.send(FrameKind.PONG, Heartbeat.reply(frame))
                    FrameKind.MODE_ACK -> { outbox.acknowledge(frame.payload); onFrame(frame) }
                    FrameKind.CLOSE -> throw EOFException("PC 세션 종료")
                    else -> onFrame(frame)
                }
            }
        } catch (error: IOException) { throw failure.get() ?: error }
        finally {
            alive.set(false)
            outbox.close()
            watchdog.shutdownNow()
            session.close()
            writer.join()
        }
    }

    // 전송 중이던 세션만 닫으면 기존 재접속 루프가 PC의 마지막 확정 순번에서 이어간다.
    fun retrySession(id: java.util.UUID) {
        active.get()?.takeIf { it.id == id }?.close()
    }

    override fun close() {
        stopped.set(true)
        active.get()?.close()
        owner.get()?.takeUnless { it === Thread.currentThread() }?.interrupt()
    }
}

class PairingRequiredException : IOException("PC에서 기기를 다시 등록해 주세요.")

private class Heartbeat {
    private val origin = System.nanoTime()
    private fun now() = (System.nanoTime() - origin) / 1000
    private data class Pending(val time: Long, val confirmed: (() -> Unit)?)
    private val pending = LinkedHashMap<String, Pending>()
    private var lastPong = 0L
    private var nonce = 0L

    @Synchronized fun expired() = now() - lastPong >= 3_000_000
    @Synchronized fun ping(confirmed: (() -> Unit)? = null): ByteArray {
        val t1 = now()
        val key = (++nonce).toString()
        pending[key] = Pending(t1, confirmed)
        return """{"nonce":"$key","t1":"$t1"}""".encodeToByteArray()
    }

    @Synchronized fun pong(frame: Frame): Long {
        val t4 = now()
        try {
            val body = JsonParser.parseString(frame.payload.decodeToString()).asJsonObject
            val probe = pending.remove(body["nonce"].asString) ?: throw IOException("요청하지 않은 Pong")
            val t1 = probe.time
            val t2 = body["t2"].asString.toLong()
            val t3 = body["t3"].asString.toLong()
            if (body["t1"].asString.toLong() != t1 || t2 < 0 || t3 < t2 || t3 - t2 > t4 - t1 + 1)
                throw IOException("Pong 시각 불일치")
            lastPong = t4
            pending.entries.removeIf { it.value.time <= t1 }
            probe.confirmed?.invoke()
            return (t4 - t1 - (t3 - t2)).coerceAtLeast(0)
        } catch (error: RuntimeException) { throw IOException("Pong 형식 오류", error) }
    }

    companion object {
        fun reply(frame: Frame): ByteArray {
            val t2 = System.nanoTime() / 1000
            try {
                val body = JsonParser.parseString(frame.payload.decodeToString()).asJsonObject
                body.addProperty("t2", t2.toString())
                body.addProperty("t3", (System.nanoTime() / 1000).toString())
                return body.toString().encodeToByteArray()
            } catch (error: RuntimeException) { throw IOException("Ping 형식 오류", error) }
        }
    }
}
