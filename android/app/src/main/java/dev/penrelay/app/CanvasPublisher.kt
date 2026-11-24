package dev.penrelay.app

import android.util.Base64
import dev.penrelay.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.roundToInt

internal class CanvasPublisher(private val document: CanvasDocument, private val queue: SessionOutbox,
    val epoch: String, initiallyVisible: Boolean = true, private val loadFailed: (String) -> Unit,
    private val confirmed: (Long, String) -> Unit) : Closeable {
    private val wake = ArrayBlockingQueue<Unit>(1)
    private var acknowledged = -1L
    private var pending: Pair<Long, String>? = null
    private var requested = false
    @Volatile private var visible = initiallyVisible
    private var ready = false
    @Volatile private var supportsPixelDelta = false
    @Volatile private var supportsInkPreview = false
    @Volatile private var supportsSavedStroke = false
    @Volatile private var supportsEraserPreview = false
    private val wholeEraser = WholeErasePublisher { id, revision, x, y, encoded ->
        val body = header().put("strokeId", id).put("revision", revision.toString())
        if (encoded == null) body.put("action", "eraseTilesReady")
        else body.put("action", "eraseTile").put("x", x).put("y", y).put("pixels", encoded)
        queue.offerOverlay(body.toString().encodeToByteArray())
    }
    private var notifiedBase = document.overlayBase
    private var richStroke = false
    private var sendingStroke: String? = null
    private val pendingCancels = ConcurrentLinkedQueue<ByteArray>()
    // 송신 스레드 전용. ACK가 돌아온 버전과 일치할 때만 차분 기준으로 사용한다.
    private var lastSentRevision = -1L
    private var lastSentPixels: IntArray? = null
    private val worker = thread(name = "canvas-publisher") {
        try {
            while (!Thread.currentThread().isInterrupted) {
                wake.take()
                while (true) {
                    val cancel = pendingCancels.poll() ?: break
                    if (!send(FrameKind.STROKE_PREVIEW, cancel)) return@thread
                }
                val base = synchronized(this) {
                    if (!visible || !ready || pending != null || (!requested && acknowledged == document.revision)) null
                    else (if (requested) -1L else acknowledged).also { requested = false }
                } ?: continue
                try {
                    if (!publish(base)) break
                } catch (e: java.io.IOException) {
                    // 다음 변경이나 스냅샷 요청에서 복원을 다시 시도한다.
                    if (!Thread.currentThread().isInterrupted) loadFailed("실시간 필기 복원 실패 · ${e.localizedMessage}")
                }
            }
        } catch (_: InterruptedException) { }
    }
    fun changed() { if (visible) wake.offer(Unit) }
    @Synchronized fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) wholeEraser.stop()
        if (value) {
            // 진행 중인 전송의 ACK를 받은 뒤 현재 문서의 전체 확정본부터 재개한다.
            requested = true
            changed()
        }
    }
    @Synchronized fun reply(kind: FrameKind, body: JSONObject) {
        if (body.optString("epoch") != epoch) return
        if (kind == FrameKind.REQUEST_SNAPSHOT) {
            wholeEraser.stop()
            supportsEraserPreview = body.optBoolean("eraserPreview1")
            supportsSavedStroke = body.optBoolean("savedStroke1")
            supportsInkPreview = body.optBoolean("inkPreview1")
            supportsPixelDelta = body.optBoolean("pixelDelta1")
            ready = true; pending = null; requested = true
        } else {
            val sent = pending ?: return
            if (body.optString("doc") != document.id || body.optString("revision") != sent.first.toString()
                || body.optString("hash") != sent.second) return
            acknowledged = sent.first
            pending = null
            confirmed(sent.first, sent.second)
        }
        changed()
    }
    private fun header() = JSONObject().put("doc", document.id).put("epoch", epoch)
    fun wholePreview(gesture: InkStroke, hits: List<InkStroke>) {
        if (visible && supportsEraserPreview) wholeEraser.update(gesture.id, document.frozen(), hits)
    }

    fun preview(ink: InkStroke, action: String, points: List<Sample>, offset: Int) {
        if (ink.brush == Brush.Whole && action == "cancel") {
            wholeEraser.stop()
            if (visible && supportsEraserPreview) cancelPreview(ink.id)
            return
        }
        if (!visible || (ink.brush.erases && !supportsEraserPreview)) return
        if (action == "begin") {
            sendingStroke = ink.id
            richStroke = supportsInkPreview
        } else if (sendingStroke != ink.id) return
        val coords = JSONArray()
        for (point in points) {
            val sample = JSONArray().put(point.x.toDouble()).put(point.y.toDouble())
            if (richStroke) sample.put((point.pressure * 4095f).roundToInt())
            coords.put(sample)
        }
        val body = header().put("strokeId", ink.id).put("action", action).put("points", coords)
            .put("revision", (document.revision + 1).toString())
        if (richStroke) body.put("offset", offset)
        if (action == "begin" || !richStroke) {
            body.put("width", ink.width.toDouble())
                .put("color", (ink.color and 0xffffff).toString(16).padStart(6, '0'))
            if (richStroke) body.put("brush", ink.brush.ordinal).put("texture", ink.texture.ordinal)
                .put("pressure", ink.pressure).put("strength", ink.pressureStrength)
        }
        val result = queue.offer(FrameKind.STROKE_PREVIEW, body.toString().encodeToByteArray())
        if (result != OfferResult.ACCEPTED) {
            sendingStroke = null
            if (result == OfferResult.PREVIEW_DROPPED && action != "begin") {
                // 누락 구간을 직선으로 이어 그리지 않는다. 취소는 송신 스레드가 빈 자리를 기다린다.
                cancelPreview(ink.id)
            }
        }
        if (action == "cancel") sendingStroke = null
    }
    private fun cancelPreview(id: String) {
        val cancel = header().put("strokeId", id).put("action", "cancel").toString().encodeToByteArray()
        if (queue.offerOverlay(cancel) == OfferResult.PREVIEW_DROPPED) {
            pendingCancels.add(cancel)
            wake.offer(Unit)
        }
    }
    // 획 저장에 성공한 뒤 호출한다. 큰 획이나 혼잡한 연결은 기존 확정 화면으로 수렴한다.
    fun savedStroke(ink: InkStroke) {
        if (!visible || !supportsSavedStroke || ink.points.size > 4096) return
        val body = header().put("action", "saved").put("strokeId", ink.id)
            .put("revision", document.revision.toString()).put("overlayBase", document.overlayBase.toString())
            .put("ink", Base64.encodeToString(ink.record(), Base64.NO_WRAP))
        queue.offerOverlay(body.toString().encodeToByteArray())
    }

    fun notifyOverlayEdit() {
        val base = document.overlayBase
        if (!visible || !supportsSavedStroke || base == notifiedBase) return
        wholeEraser.finish(document.strokeId)
        val body = header().put("previousBase", document.previousOverlayBase.toString()).put("action", "invalidateSaved").put("overlayBase", base.toString())
        if (base == document.revision && document.strokeId.isNotEmpty()) body.put("editId", document.strokeId)
        if (queue.offerOverlay(body.toString().encodeToByteArray()) == OfferResult.ACCEPTED) notifiedBase = base
    }

    private fun send(kind: FrameKind, payload: ByteArray): Boolean {
        return !Thread.currentThread().isInterrupted && queue.sendCanvasData(kind,payload)
    }
    private fun publish(base: Long): Boolean {
        val snap = document.snapshot(base)
        val rgba = ByteArray(snap.pixels.size * 4)
        for ((i, argb) in snap.pixels.withIndex()) {
            val p = i * 4
            if (argb ushr 24 == 0) continue
            rgba[p] = (argb shr 16).toByte()
            rgba[p+1] = (argb shr 8).toByte()
            rgba[p+2] = argb.toByte()
            rgba[p+3] = (argb ushr 24).toByte()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(document.width).putInt(document.height).array())
        val hash = digest.digest(rgba).joinToString("") { "%02x".format(it) }
        val transfer = UUID.randomUUID()
        val optimized = supportsPixelDelta
        val previous = lastSentPixels?.takeIf { optimized && base >= 0 && base == lastSentRevision }
        val full = previous == null && snap.full
        val pixels = if (previous != null)
            CanvasPixelPayload.changes(previous, snap.pixels, rgba, document.width, document.height)
        else CanvasPixelPayload.rectangle(snap.rect, rgba, document.width)
        val payload = if (optimized) pixels.compressIfSmaller() else pixels
        val rect = payload.rect
        val data = payload.bytes
        val length = data.size
        lastSentRevision = snap.revision
        lastSentPixels = if (optimized) snap.pixels else null
        synchronized(this) { pending = snap.revision to hash }
        val ids = JSONArray()
        if (snap.stroke.isNotEmpty()) ids.put(snap.stroke)
        val begin = header().put("transferId", transfer.toString()).put("baseRev", maxOf(base, 0).toString())
            .put("newRev", snap.revision.toString()).put("full", full).put("canvasW", document.width)
            .put("canvasH", document.height).put("rect", JSONArray(listOf(rect.left, rect.top, rect.width(), rect.height())))
            .put("byteLength", length).put("fullHash", hash).put("strokeIds", ids)
        if (supportsSavedStroke) begin.put("overlayBase", snap.overlayBase.toString())
        payload.tiles?.let { begin.put("tiles", JSONArray(it)) }
        if (payload.compressed) begin.put("encoding", "zlib")
        if (!send(FrameKind.COMMIT_BEGIN, begin.toString().encodeToByteArray())) return false
        var offset = 0
        while (offset < length) {
            val size = minOf(length - offset, 64 * 1024)
            val chunk = ByteBuffer.allocate(20 + size).putLong(transfer.mostSignificantBits)
                .putLong(transfer.leastSignificantBits).putInt(offset).put(data, offset, size)
            if (!send(FrameKind.CHUNK, chunk.array())) return false
            offset += size
        }
        return send(FrameKind.COMMIT_END, header().put("transferId", transfer.toString()).toString().encodeToByteArray())
    }
    override fun close() {
        wholeEraser.close()
        worker.interrupt()
    }
}

