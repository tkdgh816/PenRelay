package dev.penrelay.diagnostics

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File

class SensorDiagnosticsActivity : Activity() {
    private var capture: BufferedWriter? = null
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val devices = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "기록을 시작한 뒤 검은 영역에서 S펜을 사용해 주세요." }
        toggle = Button(this).apply {
            text = "기록 시작"
            setOnClickListener { if (capture == null) startCapture() else stopCapture() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(toggle)
            addView(SensorSurface(this@SensorDiagnosticsActivity), LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun startCapture() {
        val file = File.createTempFile("pen-", ".tsv", filesDir)
        capture = file.bufferedWriter().apply {
            appendLine("timeMs\tdevice\tsource\tbatchAction\tactionPointerId\tpointerId\ttool\thistorical\txPx\tyPx\tpressure\ttiltRad\torientationRad\tdistance\tbatchButtons\tactionButton\tflags\twidthPx\theightPx\trotation")
        }
        devices.clear()
        status.text = "기록 중 · " + file.name
        toggle.text = "기록 종료"
    }

    private fun stopCapture() {
        capture?.close() ?: return
        capture = null
        status.text = "저장 완료 · 앱 내부 files 폴더"
        toggle.text = "기록 시작"
    }

    override fun onPause() {
        stopCapture()
        super.onPause()
    }

    private inner class SensorSurface(context: Context) : View(context) {
        init { setBackgroundColor(Color.BLACK) }
        override fun onTouchEvent(event: MotionEvent) = record(event)
        override fun onHoverEvent(event: MotionEvent) = record(event)
        override fun onGenericMotionEvent(event: MotionEvent) = record(event)

        private fun record(event: MotionEvent): Boolean {
            val writer = capture ?: return true
            if (devices.add(event.deviceId)) {
                event.device?.motionRanges?.forEach { range ->
                    writer.appendLine("#range\t${event.deviceId}\t${range.source}\t${range.axis}\t${range.min}\t${range.max}\t${range.resolution}")
                }
            }
            for (sample in 0..event.historySize) {
                val historical = sample < event.historySize
                val time = if (historical) event.getHistoricalEventTime(sample) else event.eventTime
                for (index in 0 until event.pointerCount) {
                    fun axis(id: Int) = if (historical) event.getHistoricalAxisValue(id, index, sample)
                        else event.getAxisValue(id, index)
                    val row = listOf(time, event.deviceId, event.source, event.actionMasked,
                        event.getPointerId(event.actionIndex), event.getPointerId(index),
                        event.getToolType(index), historical, axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y),
                        axis(MotionEvent.AXIS_PRESSURE), axis(MotionEvent.AXIS_TILT),
                        axis(MotionEvent.AXIS_ORIENTATION), axis(MotionEvent.AXIS_DISTANCE),
                        event.buttonState, event.actionButton, event.flags, width, height, display?.rotation)
                    writer.appendLine(row.joinToString("\t"))
                }
            }
            return true
        }
    }
}
