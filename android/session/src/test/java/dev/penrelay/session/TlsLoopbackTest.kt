package dev.penrelay.session

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class TlsLoopbackTest {
    private val origin = System.nanoTime()
    private fun nowUs() = ((System.nanoTime() - origin) / 1000).toULong()

    @Test @Timeout(60) fun pairReconnectAndRevokeActiveSession() {
        val host = System.getProperty("p1.host")
        assumeTrue(host != null, "C# 시험 상대는 -Pp1Host로 지정합니다.")
        val process = ProcessBuilder(host, "loopback").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            process.inputStream.bufferedReader().use { pipe ->
                val registration = InetSocketAddress("127.0.0.1", pipe.readLine().toInt())
                val endpoint = InetSocketAddress("127.0.0.1", pipe.readLine().toInt())
                val serverPin = pipe.readLine()
                val store = keyStore(pipe.readLine())
                val client = client(store)
                val stranger = client(keyStore(pipe.readLine()))
                val expiredStore = keyStore(pipe.readLine())
                val expired = client(expiredStore)
                val secret = Base64.getDecoder().decode(pipe.readLine())
                val key = store.getCertificate(store.aliases().nextElement()).publicKey
                assertArrayEquals(key.encoded, expiredStore.getCertificate(expiredStore.aliases().nextElement()).publicKey.encoded)
                val devicePin = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                    .joinToString("") { "%02X".format(it) }
                val console = process.outputStream.bufferedWriter()
                fun command(text: String) { console.write(text); console.newLine(); console.flush() }

                expectRejection(client, endpoint, serverPin)
                assertThrows(SSLHandshakeException::class.java) {
                    client.register(registration, "0".repeat(64), secret)
                }
                val wrong = secret.copyOf().also { it[0] = it[0].inc() }
                assertFalse(client.register(registration, serverPin, wrong))
                val result = CompletableFuture.supplyAsync { client.register(registration, serverPin, secret) }
                assertEquals("confirm " + devicePin, pipe.readLine())
                command("approve " + devicePin)
                assertTrue(result.get(5, TimeUnit.SECONDS))
                assertFalse(stranger.register(registration, serverPin, secret))

                expectRejection(DevicePairing(emptyArray()), endpoint, serverPin)
                expectRejection(stranger, endpoint, serverPin)
                expectRejection(expired, endpoint, serverPin)
                fun open() = client.openSession(endpoint, serverPin, listOf("Digitizer", "Canvas"), emptyList())
                val first = open().use { session ->
                    exchange(session)
                    for (nonce in listOf("a", "b")) {
                        session.send(FrameKind.PING, """{"nonce":"$nonce","t1":"${nowUs()}"}""".encodeToByteArray())
                    }
                    for ((index, nonce) in listOf("a", "b").withIndex()) {
                        val pong = requireNotNull(session.receive())
                        assertEquals(FrameKind.PONG, pong.kind)
                        assertEquals((index + 2).toULong(), pong.sequence)
                        assertEquals(nonce, JsonParser.parseString(pong.payload.decodeToString()).asJsonObject["nonce"].asString)
                    }
                    session.send(FrameKind.CLOSE, """{"reason":"시험 종료"}""".encodeToByteArray())
                    session.id
                }
                checkProtocolFailures(client, endpoint, serverPin, first, ::command)
                for (scenario in listOf("major", "minor", "body", "zero-id", "welcome-sequence",
                        "old-id", "duplicate", "gap", "second-welcome")) {
                    var session: ClientSession? = null
                    try {
                        val error = assertThrows(IOException::class.java) {
                            val negotiated = open()
                            session = negotiated
                            if (scenario == "duplicate") assertEquals(1uL, negotiated.receive()?.sequence)
                            negotiated.receive()
                        }
                        assertFalse(error is SocketTimeoutException, scenario)
                        val failedDuringHandshake = scenario in listOf("major", "minor", "body", "zero-id", "welcome-sequence")
                        assertEquals(failedDuringHandshake, session == null, scenario)
                        assertEquals("client-closed " + scenario, pipe.readLine())
                        println("Kotlin 수신 거부 및 상대 종료 관찰: " + scenario)
                    } finally { session?.close() }
                }
                checkRecovery(::open)
                client.connect(endpoint, serverPin).use { silent ->
                    sendHello(silent)
                    requireNotNull(FrameCodec.read(silent.inputStream))
                    silent.outputStream.write(0x50)
                    val start = System.nanoTime()
                    expectEnd { FrameCodec.read(silent.inputStream) }
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    assertTrue(elapsed in 2500..4800, "Windows 부분 헤더 무응답: $elapsed ms")
                    println("Windows 부분 헤더 종료: $elapsed ms")
                }
                open().use { active ->
                    assertNotEquals(first, active.id)
                    exchange(active)
                    val start = System.nanoTime()
                    command("revoke")
                    assertEquals("revoked", pipe.readLine())
                    // 타임아웃은 실패다. 다른 요청이나 keepalive 없이 종료가 전달되어야 한다.
                    expectEnd { active.receive() }
                    println("PC 폐기 요청부터 유휴 클라이언트 종료 관찰까지 ${(System.nanoTime() - start) / 1000} µs")
                    command("closed")
                }
                expectRejection(client, endpoint, serverPin)
                expectRejection(client, endpoint, serverPin) // 신뢰 목록을 다시 연 호스트
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), "서버 측 인증 및 프레임 검사")
        } finally {
            process.destroyForcibly()
        }
    }

    private fun expectEnd(read: () -> Frame?) {
        try { assertNull(read(), "닫힌 연결에서 프레임 수신") }
        catch (timeout: SocketTimeoutException) { throw timeout }
        catch (_: java.net.SocketException) { }
        catch (_: SSLException) { }
    }

    private fun checkRecovery(open: () -> ClientSession) {
        val sessions = mutableListOf<SessionOutbox>()
        var retries = 0
        var rtt = false
        var ack = false
        val start = System.nanoTime()
        lateinit var current: ClientSession
        lateinit var runner: ManagedConnection
        fun done() { if (rtt && ack) runner.close() }
        runner = ManagedConnection({ true }, { open().also { current = it } }, { event ->
            when (event) {
                is ConnectionEvent.Online -> {
                    sessions.add(event.outbox)
                    if (sessions.size == 2) {
                        assertNotEquals(sessions[0].id, sessions[1].id)
                        assertEquals(OfferResult.CLOSED, sessions[0].offer(FrameKind.SET_MODE, byteArrayOf()))
                        assertEquals(0, event.outbox.stats().depth)
                        assertEquals(OfferResult.ACCEPTED, event.outbox.selectMode(TransportMode.Canvas))
                    }
                }
                is ConnectionEvent.Retry -> {
                    assertEquals(1, ++retries)
                    assertEquals("Pong 3초 무응답", event.reason)
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    assertTrue(elapsed in 2900..4800)
                    println("Kotlin 무응답 종료: $elapsed ms, 재접속 대기: ${event.delayMillis} ms")
                }
                is ConnectionEvent.RoundTrip -> { assertTrue(event.micros >= 0); rtt = true; done() }
                else -> fail<Unit>("예상하지 않은 연결 상태: $event")
            }
        }, { frame ->
            assertEquals(FrameKind.MODE_ACK, frame.kind)
            val body = JsonParser.parseString(frame.payload.decodeToString()).asJsonObject
            val queue = sessions.last()
            when (body["modeEpoch"].asString) {
                "1" -> {
                    assertEquals("Canvas", body["mode"].asString)
                    assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.STROKE_PREVIEW, """{"epoch":"1"}""".encodeToByteArray()))
                    assertEquals(OfferResult.ACCEPTED, queue.selectMode(TransportMode.Digitizer))
                }
                "2" -> {
                    assertEquals("Digitizer", body["mode"].asString)
                    assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.PEN_BATCH,
                        """{"epoch":"2","samples":[{"action":"down"}]}""".encodeToByteArray()))
                    assertEquals(OfferResult.ACCEPTED, queue.selectMode(TransportMode.Canvas))
                }
                "3" -> {
                    // 전환 전에 생성된 PenBatch를 늦게 전달한다. C# 호스트가 세는 펜 호출은 여전히 1회다.
                    current.send(FrameKind.PEN_BATCH, """{"epoch":"2","samples":[{"action":"move"}]}""".encodeToByteArray())
                    assertEquals(OfferResult.ACCEPTED, queue.selectMode(TransportMode.Canvas))
                }
                "4" -> ack = true
                else -> fail<Unit>("예상하지 않은 모드 ACK")
            }
            done()
        })
        runner.use { it.run() }
        assertTrue(ack && rtt && sessions.size == 2 && retries == 1)
    }

    private fun expectRejection(client: DevicePairing, endpoint: InetSocketAddress, serverPin: String) {
        expectEnd {
            client.connect(endpoint, serverPin).use { socket -> sendHello(socket); FrameCodec.read(socket.inputStream) }
        }
    }

    private fun exchange(session: ClientSession) {
        assertEquals(FrameCodec.MAX_PAYLOAD, session.maxPayload)
        val t1 = nowUs()
        val ping = """{"nonce":"p1","t1":"$t1"}""".encodeToByteArray()
        session.send(FrameKind.PING, ping)
        val pong = requireNotNull(session.receive())
        val t4 = nowUs()
        assertEquals(FrameKind.PONG, pong.kind)
        assertEquals(session.id, pong.sessionId)
        assertEquals(1uL, pong.sequence)
        val reply = JsonParser.parseString(pong.payload.decodeToString()).asJsonObject
        assertEquals("p1", reply["nonce"].asString)
        assertEquals(t1.toString(), reply["t1"].asString)
        assertTrue(reply["t3"].asString.toULong() >= reply["t2"].asString.toULong())
        assertTrue(t4 >= t1)
    }

    private fun checkProtocolFailures(client: DevicePairing, endpoint: InetSocketAddress, pin: String,
                                      previousId: UUID, command: (String) -> Unit) {
        for (scenario in listOf("major", "minor", "versions", "first-frame", "old-id", "duplicate", "gap", "second-hello")) {
            client.connect(endpoint, pin).use { socket ->
                assertEquals("TLSv1.3", socket.session.protocol)
                when (scenario) {
                    "major" -> sendHello(socket, """[{"major":2,"minor":0}]""")
                    "minor" -> sendHello(socket, """[{"major":1,"minor":1}]""")
                    "versions" -> sendHello(socket, """[{"major":1,"minor":"0"}]""")
                    "first-frame" -> FrameCodec.write(socket.outputStream,
                        Frame(FrameKind.PING, UUID(0, 0), 0uL, nowUs(), "{}".encodeToByteArray()))
                    else -> {
                        sendHello(socket, """[{"major":1,"minor":3},{"major":1,"minor":0}]""")
                        val welcome = requireNotNull(FrameCodec.read(socket.inputStream))
                        assertEquals(FrameKind.WELCOME, welcome.kind)
                        assertNotEquals(previousId, welcome.sessionId)
                        val selected = JsonParser.parseString(welcome.payload.decodeToString()).asJsonObject
                        assertEquals(1, selected["major"].asInt)
                        assertEquals(0, selected["minor"].asInt)
                        fun ping(id: UUID, seq: ULong) = Frame(FrameKind.PING, id, seq, nowUs(), "{}".encodeToByteArray())
                        val current = ping(welcome.sessionId, 1uL)
                        when (scenario) {
                            "old-id" -> FrameCodec.write(socket.outputStream, ping(previousId, 1uL))
                            "duplicate" -> repeat(2) { FrameCodec.write(socket.outputStream, current) }
                            "gap" -> {
                                val bytes = ByteArrayOutputStream()
                                FrameCodec.write(bytes, ping(welcome.sessionId, 2uL))
                                socket.outputStream.write(bytes.toByteArray(), 0, FrameCodec.HEADER_SIZE)
                            }
                            "second-hello" -> FrameCodec.write(socket.outputStream,
                                Frame(FrameKind.HELLO, welcome.sessionId, 1uL, nowUs(), "{}".encodeToByteArray()))
                        }
                    }
                }
                expectEnd { FrameCodec.read(socket.inputStream) }
                command("closed " + scenario)
                println("Windows 수신 거부 및 상대 종료 관찰: " + scenario)
            }
        }
    }

    private fun sendHello(socket: SSLSocket, versions: String = """[{"major":1,"minor":0}]""") {
        val payload = """{"versions":$versions,"modes":["Digitizer"],"sensorCaps":[]}"""
        val wire = ByteArrayOutputStream()
        FrameCodec.write(wire, Frame(FrameKind.HELLO, UUID(0, 0), 0uL, nowUs(), payload.encodeToByteArray()))
        val bytes = wire.toByteArray()
        for (i in 0 until FrameCodec.HEADER_SIZE) socket.outputStream.write(bytes[i].toInt())
        socket.outputStream.write(bytes, FrameCodec.HEADER_SIZE, bytes.size - FrameCodec.HEADER_SIZE)
    }

    private fun keyStore(encoded: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(Base64.getDecoder().decode(encoded).inputStream(), charArrayOf())
    }

    private fun client(store: KeyStore): DevicePairing {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, charArrayOf())
        return DevicePairing(keys.keyManagers)
    }
}
