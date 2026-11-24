package dev.penrelay.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class TransportTest {
    @Test fun directRequiresAResolvedGroupInterface() {
        java.net.Socket().use { socket ->
            val endpoint = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 47120)
            assertThrows(java.net.SocketException::class.java) { ConnectionPath.Direct.bind(socket, endpoint) }
            assertFalse(socket.isBound)
        }
    }

    @Test fun boundedBySlotsAndWireBytes() {
        val preview = """{"epoch":"1"}""".encodeToByteArray()
        val input = ByteArray(21)
        val queue = SessionOutbox(UUID.randomUUID(), 1024, slots = 2, byteLimit = 130)
        assertEquals(OfferResult.ACCEPTED, queue.selectMode(TransportMode.Canvas))
        assertEquals(OfferResult.MODE_NOT_READY, queue.offer(FrameKind.CHUNK, input))
        queue.poll(0)
        queue.acknowledge("""{"mode":"Canvas","modeEpoch":"1"}""".encodeToByteArray())
        assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.CHUNK, input))
        input[20] = 1
        assertEquals(OfferResult.BUSY, queue.offer(FrameKind.CHUNK, ByteArray(22)))
        assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.CHUNK, input))
        repeat(500) { assertEquals(OfferResult.PREVIEW_DROPPED, queue.offer(FrameKind.STROKE_PREVIEW, preview)) }
        assertEquals(QueueStats(2, 130, 500), queue.stats())
        assertEquals(0.toByte(), queue.poll(0)!!.payload[20])
        assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.CHUNK, input))
        assertArrayEquals(input, queue.poll(0)!!.payload)
        queue.close()
        assertNull(queue.poll(0))
        assertEquals(QueueStats(0, 0, 500), queue.stats())
        assertEquals(OfferResult.CLOSED, queue.offer(FrameKind.PEN_BATCH, input))
    }

    @Test fun freshDownAfterReconnect() {
        val queue = SessionOutbox(UUID.randomUUID(), 1024)
        fun samples(vararg actions: String) = """{"epoch":"1","samples":[${actions.joinToString(",") { """{"action":"$it"}""" }}]}""".encodeToByteArray()
        queue.selectMode(TransportMode.Digitizer)
        queue.poll(0)
        queue.acknowledge("""{"mode":"Digitizer","modeEpoch":"1"}""".encodeToByteArray())
        assertEquals(OfferResult.CONTACT_SKIPPED, queue.offer(FrameKind.PEN_BATCH, samples("move", "up")))
        assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.PEN_BATCH, samples("move", "up", "down", "move", "button", "cancel")))
        assertArrayEquals(samples("down", "move", "button", "cancel"), queue.poll(0)!!.payload)
    }

    @Test fun productionCapacityWithSlowConsumer() {
        val queue = SessionOutbox(UUID.randomUUID(), FrameCodec.MAX_PAYLOAD)
        queue.selectMode(TransportMode.Canvas)
        queue.poll(0)
        queue.acknowledge("""{"mode":"Canvas","modeEpoch":"1"}""".encodeToByteArray())
        repeat(64) { assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.CHUNK, ByteArray(21))) }
        assertEquals(OfferResult.BUSY, queue.offer(FrameKind.CHUNK, ByteArray(21)))
        assertEquals(64, queue.stats().depth)
        repeat(64) { queue.poll(0) }
        val large = ByteArray(20 + 256 * 1024)
        repeat(3) { assertEquals(OfferResult.ACCEPTED, queue.offer(FrameKind.CHUNK, large)) }
        assertEquals(OfferResult.BUSY, queue.offer(FrameKind.CHUNK, large))
        assertEquals(3 * (FrameCodec.HEADER_SIZE + large.size), queue.stats().bytes)
    }

    @Test fun retryBoundsAndTerminalFailures() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 6000L, 6000L), (0..5).map { retryDelay(it, 0.0) })
        assertTrue(retryDelay(0, 0.5) > retryDelay(0, 0.0))
        assertEquals(7980L, retryDelay(100, 0.99))
        for (permission in listOf(false, true)) {
            var opens = 0
            val events = mutableListOf<ConnectionEvent>()
            ManagedConnection({ permission }, { opens++; throw PairingRequiredException() }, { events.add(it) }).run()
            assertEquals(if (permission) 1 else 0, opens)
            assertEquals(listOf(if (permission) ConnectionEvent.PairingRequired else ConnectionEvent.PermissionRequired), events)
        }
        assertTrue(LanPermission.allowed(36, 37) { error("API 36 권한 조회") })
        assertFalse(LanPermission.allowed(37, 37) { false })
        assertTrue(LanPermission.allowed(37, 37) { true })
    }

    @Test fun pageProducerWakesOnDequeueAndClose() {
        val queue = SessionOutbox(UUID.randomUUID(),1024)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun send(value: Int) = worker.submit<Boolean> { queue.sendCanvasData(FrameKind.PAGE_CHUNK,ByteArray(32) { value.toByte() }) }
        try {
            assertTrue(send(1).get(2,TimeUnit.SECONDS))
            val second = send(2)
            assertEquals(1,queue.poll(0)!!.payload.first().toInt())
            assertTrue(second.get(2,TimeUnit.SECONDS))
            val third = send(3)
            queue.close()
            assertFalse(third.get(2,TimeUnit.SECONDS))
        } finally { queue.close(); worker.shutdownNow() }
    }
}
