package dev.penrelay.app

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.penrelay.session.OfferResult
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class PenTrial(private val test: Instrumentation, private val activity: Activity,
    private val status: (String) -> Unit) {
    private val root get() = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun surface(v: View = root): View? {
        if (v.contentDescription == "S펜 입력면") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) surface(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(timeout: Long = 15000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(40)
        }
        error("P2 태블릿 단계 시간 초과")
    }
    private fun ready() = await {
        var ok = false
        test.runOnMainSync { ok = surface()?.width?.let { it > 1 } == true }
        ok
    }
    private fun guide(text: String) = test.runOnMainSync {
        val parent = surface()!!.parent as android.widget.LinearLayout
        (parent.findViewWithTag<TextView>("p2-guide") ?: TextView(activity).apply {
            tag = "p2-guide"; textSize = 22f; parent.addView(this, 0)
        }).text = text
    }
    fun run(replay: Boolean, latencyTrials: Int, click: (String) -> Unit) {
        ready()
        val command = File(activity.cacheDir, "p2-command")
        fun waitCommand(value: String) = await(600_000) { command.exists() && command.readText().trim() == value }
        if (replay) {
            status("P2-REPLAY")
            val rows = recordedRows()
            replay(rows)
            if (latencyTrials > 0) measureStroke(rows, latencyTrials)
            status("P2-REPLAY-END")
            waitCommand("done")
        } else {
            for ((index, message) in listOf(
                "1/3  검은 영역 중앙에서 S펜을 대고 화면이 바뀔 때까지 유지하세요.",
                "2/3  펜을 뗀 뒤 다시 대고 Canvas 전환까지 유지하세요.",
                "3/3  펜을 뗐다가 짧은 선 한 획을 그은 뒤 떼세요.").withIndex()) {
                ready()
                guide(message)
                var original: View? = null
                test.runOnMainSync { original = surface() }
                status("P2-READY-" + (index + 1))
                waitCommand("stage-" + (index + 1))
                if (index == 0) {
                    // PC가 접촉 중 EOF를 만들고 앱이 새 세션에서 입력면을 다시 만든다.
                    await { var changed = false; test.runOnMainSync { changed = surface() != null && surface() !== original }; changed }
                }
                if (index == 1) {
                    test.runOnMainSync { click("캔버스") }
                    status("P2-CANVAS-REQUEST")
                    await { var ok = false; test.runOnMainSync {
                        val found = arrayListOf<View>()
                        root.findViewsWithText(found, "캔버스", View.FIND_VIEWS_WITH_TEXT)
                        ok = found.any { it is TextView && it !is android.widget.Button && it.text == "캔버스" }
                    }; ok }
                    status("P2-CANVAS")
                    waitCommand("resume")
                    test.runOnMainSync { click("디지타이저") }
                }
            }
        }
    }
    private fun recordedRows() = File(activity.cacheDir, "p2.tsv").useLines { lines ->
        lines.filter { it.firstOrNull()?.isDigit() == true }.map { it.split('\t') }.toList()
    }
    fun desktopTrial(automatic: Boolean) {
        ready()
        if (automatic) replay(recordedRows()) else guide("디지타이저 시험 · 검은 면 중앙에 S펜으로 한 획을 그어 주세요.")
        status("P5-PEN")
        val signal = File(activity.cacheDir, "p5-pen-done")
        await(180_000) { signal.exists() }
        signal.delete()
    }
    private fun measureStroke(rows: List<List<String>>, count: Int) {
        // 녹화한 두 번째 S펜 DOWN부터 UP까지 센서 축, history와 재생 간격을 그대로 유지한다.
        val start = rows.indices.filter { rows[it][3] == "0" && rows[it][6] == "2" }[1]
        val end = (start + 1 until rows.size).first { rows[it][3] == "1" && rows[it][6] == "2" }
        val stroke = rows.subList(start, end + 1)
        val activity = activity as ConnectionActivity
        val box = activity.outbox!!
        val acknowledgments = LinkedBlockingQueue<Long>()
        val times = mutableListOf<Long>()
        repeat(count + 1) { index ->
            val up = replay(stroke) {
                check(box.confirmDelivery { acknowledgments.offer(System.nanoTime()) } == OfferResult.ACCEPTED)
            }
            val ack = acknowledgments.poll(15, TimeUnit.SECONDS) ?: error("획 처리 후 Pong 시간 초과")
            check(ack >= up && activity.outbox === box)
            if (index > 0) times.add((ack - up) / 1000)
        }
        status("P4-PEN-US " + times.joinToString(","))
    }
    private fun replay(rows: List<List<String>>, afterUp: (() -> Unit)? = null): Long {
        val device = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
            .first { it.supportsSource(InputDevice.SOURCE_STYLUS) && it.getMotionRange(MotionEvent.AXIS_TILT) != null }
        val start = SystemClock.uptimeMillis()
        val offset = rows.first()[0].toLong()
        var down = start
        var lifted = 0L
        val group = mutableListOf<List<String>>()
        for (row in rows) {
            group.add(row)
            if (row[7] == "true") continue
            val time = start + row[0].toLong() - offset
            val delay = time - SystemClock.uptimeMillis()
            if (delay > 0) SystemClock.sleep(delay)
            test.runOnMainSync {
                val view = surface() ?: error("재생 도중 연결 종료")
                val action = row[3].toInt()
                if (action == MotionEvent.ACTION_DOWN) down = time
                val properties = MotionEvent.PointerProperties().apply { id = row[5].toInt(); toolType = row[6].toInt() }
                fun coords(r: List<String>) = MotionEvent.PointerCoords().apply {
                    x = r[8].toFloat() * view.width / r[17].toFloat()
                    y = r[9].toFloat() * view.height / r[18].toFloat()
                    pressure = r[10].toFloat()
                    setAxisValue(MotionEvent.AXIS_TILT, r[11].toFloat())
                    orientation = r[12].toFloat()
                }
                val first = group.first()
                val event = MotionEvent.obtain(down, start + first[0].toLong() - offset, action, 1,
                    arrayOf(properties), arrayOf(coords(first)), 0, row[14].toInt(), 1f, 1f, device.id,
                    0, row[2].toInt(), row[16].toInt())
                try {
                    for (r in group.drop(1)) event.addBatch(start + r[0].toLong() - offset, arrayOf(coords(r)), 0)
                    if (action == MotionEvent.ACTION_UP) lifted = System.nanoTime()
                    if (action in listOf(0, 1, 2, 3, 5, 6)) view.dispatchTouchEvent(event)
                    else view.dispatchGenericMotionEvent(event)
                    if (action == MotionEvent.ACTION_UP) afterUp?.invoke()
                } finally { event.recycle() }
            }
            group.clear()
        }
        check(group.isEmpty())
        return lifted
    }
}
