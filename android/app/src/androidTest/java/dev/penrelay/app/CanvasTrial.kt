package dev.penrelay.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class CanvasTrial(private val test: Instrumentation, private val activity: Activity,
    private val status: (String) -> Unit) {
    private val root get() = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun canvas(v: View = root): CanvasSurface? {
        if (v is CanvasSurface) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) canvas(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(label: String, timeout: Long = 45000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            test.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        }
        error("$label 대기 시간 초과")
    }
    private fun confirmed(label: String) = (activity as ConnectionActivity).canvasAck.contains(label)
    private fun guide(message: String) = test.runOnMainSync {
        val panel = canvas()!!.parent as LinearLayout
        (panel.findViewWithTag<TextView>("guide") ?: TextView(activity).apply {
            textSize = 22f; tag = "guide"; panel.addView(this, 0)
        }).text = message
    }
    private fun fixture(view: CanvasSurface, stage: Int, interval: Long = 60): Long {
        val down = SystemClock.uptimeMillis()
        var lifted = 0L
        for (n in 0..10) {
            test.runOnMainSync {
                val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coords = MotionEvent.PointerCoords().apply {
                    x = view.width * (.32f + n * .025f); y = view.height * (.22f + stage * .15f + n * .01f)
                    pressure = .5f
                }
                val action = when (n) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(p), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                if (n == 10) lifted = System.nanoTime()
                try { view.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            if (n != 10) SystemClock.sleep(interval)
        }
        return lifted
    }

    fun desktopTrial(automatic: Boolean) {
        await("Windows 최초 표시 준비") { confirmed("PC r0 ·") && canvas()?.width?.let { it > 0 } == true }
        lateinit var view: CanvasSurface
        test.runOnMainSync { view = canvas()!! }
        guide("Canvas 시험 · 흰 면 중앙에 S펜으로 한 획을 그어 주세요.")
        status("P5-CANVAS")
        if (automatic) fixture(view, 1)
        await("S펜 확정", 180000) { view.document.revision > 0 }
        val image = view.document.snapshot(-1)
        val buffer = ByteBuffer.allocate(8 + image.pixels.size * 4)
        buffer.putInt(view.document.width).putInt(view.document.height)
        image.pixels.forEach { buffer.putInt(Integer.rotateLeft(it, 8)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
        check(image.pixels.any { it ushr 24 != 0 })
        await("PC 해시") { confirmed("PC r${image.revision} · $hash") }
        status("P5-CONFIRMED ${image.revision} $hash")
    }

    fun benchmark(recovery: String) {
        await("최초 전체 이미지") { confirmed("PC r0 ·") && canvas()?.width?.let { it > 0 } == true }
        val app = activity as ConnectionActivity
        lateinit var view: CanvasSurface
        data class Ack(val revision: Long, val hash: String, val nano: Long)
        val acks = LinkedBlockingQueue<Ack>()
        test.runOnMainSync {
            view = canvas()!!
            app.onCanvasAck = { revision,hash -> acks.offer(Ack(revision,hash,System.nanoTime())) }
        }
        fun ack(revision: Long, seconds: Long = 30): Ack {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val result = acks.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: error("r$revision 확인 시간 초과")
                if (result.revision == revision) return result
                check(result.revision < revision)
            }
        }
        try {
            fixture(view, 1, 10)
            ack(1)
            if (recovery != "none") {
                val original = app.outbox!!.id
                status("READY-CABLE")
                await("USB 단절", 600000) { app.outbox == null }
                fixture(view, 2, 10)
                check(view.document.revision == 2L)
                status("OFFLINE-STROKE")
                val restored = ack(2, 180)
                check(app.outbox!!.id != original)
                val next = fixture(view, 3, 10)
                val latest = ack(3)
                check(latest.nano >= next && latest.hash != restored.hash)
                status("RESTORED ${restored.hash} ${latest.hash}")
                return
            }
            for (round in 0..2) {
                val waits = java.util.Collections.synchronizedList(mutableListOf<Long>())
                val trips = mutableListOf<Long>()
                val stroke = mutableListOf<Long>()
                val hashes = mutableListOf<String>()
                val box = app.outbox!!
                var bytes = 0L
                var depth = 0
                val dropped = box.stats().droppedPreviews
                test.runOnMainSync { app.onRoundTrip = { trips.add(it) } }
                box.onDequeued = { us, size, n -> bytes += size; depth = maxOf(depth, n); waits.add(us) }
                val cpu = android.os.Process.getElapsedCpuTime()
                val start = SystemClock.elapsedRealtime()
                repeat(30) { index ->
                    val up = fixture(view, 1, 10)
                    val reply = ack((2 + round * 30 + index).toLong())
                    stroke.add((reply.nano - up) / 1000)
                    hashes.add(reply.hash)
                    val remaining = start + (index + 1) * 1000 - SystemClock.elapsedRealtime()
                    if (remaining > 0) SystemClock.sleep(remaining)
                }
                box.onDequeued = null
                test.runOnMainSync { app.onRoundTrip = null }
                check(app.outbox === box && trips.isNotEmpty())
                status("ROUND $round ${SystemClock.elapsedRealtime() - start} ${android.os.Process.getElapsedCpuTime() - cpu} $bytes $depth ${box.stats().droppedPreviews - dropped}")
                status("RTT " + trips.joinToString(","))
                status("QUEUE " + waits.joinToString(","))
                status("STROKE " + stroke.joinToString(","))
                status("HASH " + hashes.joinToString(","))
            }
        } finally {
            test.runOnMainSync {
                app.onRoundTrip = null
                app.outbox?.onDequeued = null
                app.onCanvasAck = null
            }
        }
    }
    fun run(automatic: Boolean) {
        await("첫 스냅샷") { canvas()?.width?.let { it > 0 } == true && confirmed("PC r0") }
        lateinit var view: CanvasSurface
        test.runOnMainSync { view = canvas()!! }
        val doc = view.document
        status("PAGE-SIZE ${doc.width} ${doc.height}")
        var previous = IntArray(doc.width * doc.height)
        var finalHash = ""
        for (stage in 1..2) {
            if (stage == 2) await("실제 세션 종료") { (activity as ConnectionActivity).outbox == null }
            guide(if (stage == 1) "연결됨 · 흰 면 왼쪽에 S펜으로 선을 천천히 한 획 그어 주세요."
                  else "오프라인 · 흰 면 오른쪽에 S펜으로 다른 선을 한 획 그어 주세요.")
            status("READY-$stage")
            if (automatic) fixture(view, stage)
            await("S펜 $stage", 600000) { doc.revision >= stage }
            val snapshot = doc.snapshot(-1)
            check(snapshot.revision == stage.toLong())
            val pixels = snapshot.pixels
            val ink = pixels.count { it ushr 24 > 0 }
            check(pixels.indices.all { previous[it] ushr 24 <= pixels[it] ushr 24 })
            check(pixels.indices.any { pixels[it] ushr 24 > 0 && previous[it] == 0 })
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(ByteBuffer.allocate(8).putInt(doc.width).putInt(doc.height).array())
            val row = ByteBuffer.allocate(doc.width * 4)
            for (y in 0 until doc.height) {
                row.clear()
                for (x in 0 until doc.width) row.putInt(Integer.rotateLeft(pixels[y * doc.width + x], 8))
                hash.update(row.array())
            }
            finalHash = hash.digest().joinToString("") { "%02x".format(it) }
            status("LOCAL $stage $finalHash $ink")
            previous = pixels
            guide("확정 획 $stage · PC 확인 중")
        }
        await("재연결 후 같은 해시") { confirmed("PC r2 · $finalHash") }
        status("MATCH $finalHash")
    }
}

