package dev.penrelay.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import dev.penrelay.session.OfferResult
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.floor

// Android가 판정한 삭제 대상만 제외하고 겹쳐 있던 나머지 획을 원래 순서대로 그린다.
internal class WholeErasePublisher(
    private val send: (String, Long, Int, Int, String?) -> OfferResult
) : Closeable {
    private class Gesture(val id: String, val page: PageImage) {
        val excluded = HashSet<String>()
        val pending = ArrayList<InkStroke>()
        var running = false
        var finished = false
    }
    private val worker = Executors.newSingleThreadExecutor()
    private var current: Gesture? = null
    private var closed = false

    @Synchronized fun update(id: String, page: PageImage, hits: List<InkStroke>) {
        if (closed || hits.isEmpty()) return
        val gesture = current?.takeIf { it.id == id && it.page === page }
            ?: Gesture(id, page).also { current = it }
        for (stroke in hits) if (gesture.excluded.add(stroke.id)) gesture.pending.add(stroke)
        if (!gesture.running && gesture.pending.isNotEmpty()) {
            gesture.running = true
            worker.execute { drain(gesture) }
        }
    }

    private fun drain(gesture: Gesture) {
        val page = gesture.page
        val columns = (page.width + TILE - 1) / TILE
        while (true) {
            val (hits, excluded) = synchronized(this) {
                if (current !== gesture || gesture.pending.isEmpty()) {
                    if (current === gesture && gesture.finished) current = null
                    gesture.running = false
                    return
                }
                gesture.pending.toList().also { gesture.pending.clear() } to gesture.excluded.toHashSet()
            }
            val tiles = linkedSetOf<Int>()
            for (hit in hits) for (section in hit.hitSections) {
                if (synchronized(this) { current !== gesture }) return
                val box = section.bounds
                val left = floor(box.left / TILE).toInt().coerceAtLeast(0)
                val top = floor(box.top / TILE).toInt().coerceAtLeast(0)
                val right = ceil(box.right / TILE).toInt().coerceAtMost(columns)
                val bottom = ceil(box.bottom / TILE).toInt().coerceAtMost((page.height + TILE - 1) / TILE)
                for (y in top until bottom) for (x in left until right) tiles.add(y * columns + x)
            }
            for (tile in tiles) {
                if (synchronized(this) { current !== gesture }) return
                val x = tile % columns * TILE
                val y = tile / columns * TILE
                if (!offer(gesture, x, y, render(page, excluded, x, y))) return
            }
            if (tiles.isNotEmpty() && !offer(gesture, 0, 0, null)) return
        }
    }

    private fun offer(gesture: Gesture, x: Int, y: Int, pixels: String?): Boolean {
        while (true) {
            val result = synchronized(this) {
                if (current !== gesture) return false
                send(gesture.id, gesture.page.revision + 1, x, y, pixels)
            }
            when (result) {
                OfferResult.ACCEPTED -> return true
                OfferResult.PREVIEW_DROPPED, OfferResult.BUSY ->
                    try { Thread.sleep(8) } catch (_: InterruptedException) { return false }
                else -> {
                    synchronized(this) { if (current === gesture) current = null }
                    return false
                }
            }
        }
    }

    private fun render(page: PageImage, excluded: Set<String>, x: Int, y: Int): String {
        val w = minOf(TILE, page.width - x)
        val h = minOf(TILE, page.height - y)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(-x.toFloat(), -y.toFloat())
            val bounds = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (stroke in page.strokes) {
                if (stroke.id !in excluded && RectF.intersects(bounds, stroke.bounds)) stroke.drawOnPaper(canvas, paint)
            }
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally { bitmap.recycle() }
        val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asIntBuffer().put(pixels)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(bytes.array()) }
        return Base64.encodeToString(compressed.toByteArray(), Base64.NO_WRAP)
    }

    @Synchronized fun finish(id: String) {
        val gesture = current ?: return
        if (gesture.id == id && gesture.running) gesture.finished = true else current = null
    }

    @Synchronized fun stop() { current = null }
    @Synchronized override fun close() {
        closed = true
        current = null
        worker.shutdownNow()
    }

    companion object { private const val TILE = 64 }
}
