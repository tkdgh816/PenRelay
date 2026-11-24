package dev.penrelay.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs

/** 입력면 좌표에서 여백을 제거한 후 회전과 세 점 affine 보정을 적용한다. */
internal class PenSurface(context: Context, private val targetWidth: Int, private val targetHeight: Int,
    private val monitorKey: String? = null, initialRotation: Int = 0, initialAspect: Boolean = true,
    private var requestCalibration: Boolean = false,
    private val send: (JSONArray) -> Unit) : View(context) {
    private val area = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reference = listOf(.1f to .1f, .9f to .1f, .1f to .9f)
    private val taps = mutableListOf<Pair<Float, Float>>()
    private var calibrating = false
    private var rotationSteps = initialRotation.coerceIn(0, 3)
    private var keepAspect = initialAspect
    private val settings = context.getSharedPreferences("pen-calibration", Context.MODE_PRIVATE)
    private fun calibrationKey() = monitorKey?.let { "$it-$targetWidth-$targetHeight-$width-$height-${display?.rotation}-$rotationSteps-$keepAspect" }
    private var owner: Int? = null
    private var contact = false
    private var lastU = 0f
    private var lastV = 0f
    init { setBackgroundColor(Color.BLACK); contentDescription = "S펜 입력면" }

    fun rotate() { rotationSteps = (rotationSteps + 1) % 4; resetArea() }
    fun toggleAspect() { keepAspect = !keepAspect; resetArea() }
    fun calibrate() { cancelPen(); taps.clear(); calibrating = true; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { resetArea() }
    private fun resetArea() {
        cancelPen()
        taps.clear()
        calibrating = false
        val ratio = if (rotationSteps % 2 == 0) targetWidth.toFloat() / targetHeight else targetHeight.toFloat() / targetWidth
        val w = if (keepAspect) minOf(width.toFloat() - 1, (height - 1) * ratio) else width - 1f
        val h = if (keepAspect) w / ratio else height - 1f
        val left = (width - 1 - w) / 2
        val top = (height - 1 - h) / 2
        area.set(left, top, left + w, top + h)
        val stored = calibrationKey()?.let { settings.getString(it, null) }
        val points = stored?.split(',')?.mapNotNull { it.toFloatOrNull()?.takeIf { n -> n.isFinite() && n in 0f..1f } }
        if (points?.size == 6) {
            points.chunked(2).forEach { taps.add(it[0] to it[1]) }
            if (abs(determinant()) < .01f) taps.clear()
        }
        if (requestCalibration && width > 1 && height > 1) {
            requestCalibration = false
            calibrate()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.DKGRAY)
        paint.color = Color.BLACK
        canvas.drawRect(area, paint)
        if (calibrating) {
            val (u, v) = reference[taps.size]
            paint.color = Color.WHITE
            canvas.drawCircle(area.left + u * area.width(), area.top + v * area.height(), 12f, paint)
        }
    }

    private fun cancelPen() {
        owner?.let { id -> send(JSONArray().put(point(id, "cancel", android.os.SystemClock.uptimeMillis(), lastU, lastV, 0))) }
        owner = null
        contact = false
    }
    private fun point(id: Int, action: String, ms: Long, u: Float, v: Float, buttons: Int) = JSONObject()
        .put("id", id).put("action", action).put("sampleUs", (ms * 1000).toString())
        .put("u", u.toDouble()).put("v", v.toDouble()).put("buttons", buttons)

    private fun determinant() = (taps[1].first - taps[0].first) * (taps[2].second - taps[0].second) -
        (taps[2].first - taps[0].first) * (taps[1].second - taps[0].second)

    private fun coordinates(x: Float, y: Float, down: Boolean): Pair<Float, Float>? {
        var u = (x - area.left) / area.width()
        var v = (y - area.top) / area.height()
        if (u !in 0f..1f || v !in 0f..1f) return null
        if (calibrating) {
            if (down) {
                taps.add(u to v)
                if (taps.size == 3) {
                    if (abs(determinant()) < .01f) taps.clear() else {
                        calibrating = false
                        calibrationKey()?.let { key -> settings.edit().putString(key,
                            taps.joinToString(",") { "${it.first},${it.second}" }).apply() }
                    }
                }
                invalidate()
            }
            return null
        }
        if (taps.size == 3) {
            val dx = u - taps[0].first
            val dy = v - taps[0].second
            u = .1f + .8f * (dx * (taps[2].second - taps[0].second) - dy * (taps[2].first - taps[0].first)) / determinant()
            v = .1f + .8f * (dy * (taps[1].first - taps[0].first) - dx * (taps[1].second - taps[0].second)) / determinant()
        }
        if (u !in 0f..1f || v !in 0f..1f) return null
        return when (rotationSteps) { 1 -> 1f-v to u; 2 -> 1f-u to 1f-v; 3 -> v to 1f-u; else -> u to v }
    }

    override fun onTouchEvent(event: MotionEvent) = collect(event)
    override fun onHoverEvent(event: MotionEvent) = collect(event)
    override fun onGenericMotionEvent(event: MotionEvent) = collect(event)
    private fun collect(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) { cancelPen(); return true }
        val move = e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        val i = if (move) (0 until e.pointerCount).firstOrNull { e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS } ?: return true else e.actionIndex
        if (e.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS) return true
        val id = e.getPointerId(i)
        if (owner != null && owner != id) return true
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "down"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.flags and MotionEvent.FLAG_CANCELED != 0) "cancel" else "up"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_HOVER_ENTER -> "enter"
            MotionEvent.ACTION_HOVER_MOVE -> "hover"
            MotionEvent.ACTION_HOVER_EXIT -> "leave"
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> "button"
            else -> return true
        }
        val buttons = if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) 1 else 0
        var batch = JSONArray()
        fun flush() { if (batch.length() > 0) send(batch); batch = JSONArray() }
        val history = if (move) e.historySize else 0
        for (h in 0..history) {
            fun axis(a: Int) = if (h < history) e.getHistoricalAxisValue(a, i, h) else e.getAxisValue(a, i)
            val xy = coordinates(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y), action == "down")
            if (xy == null || action == "cancel") { flush(); cancelPen(); continue }
            if ((action == "move" || action == "up") && !contact) continue
            if ((action == "leave" || action == "button") && owner == null) continue
            if (action == "hover" && contact) { flush(); cancelPen() }
            val (u, v) = xy
            val ms = if (h < history) e.getHistoricalEventTime(h) else e.eventTime
            val p = point(id, action, ms, u, v, buttons)
            fun supported(a: Int) = e.device?.getMotionRange(a, e.source)
            supported(MotionEvent.AXIS_PRESSURE)?.let { r ->
                val raw = axis(MotionEvent.AXIS_PRESSURE)
                if (r.range > 0 && raw.isFinite()) p.put("pressure", ((raw-r.min)/r.range).coerceIn(0f, 1f).toDouble())
            }
            if (supported(MotionEvent.AXIS_TILT) != null && supported(MotionEvent.AXIS_ORIENTATION) != null) {
                val tilt = axis(MotionEvent.AXIS_TILT).toDouble()
                val angle = axis(MotionEvent.AXIS_ORIENTATION) + rotationSteps * PI / 2
                if (tilt.isFinite() && angle.isFinite()) {
                    p.put("tilt", tilt.coerceIn(0.0, PI/2))
                    p.put("orientation", kotlin.math.atan2(kotlin.math.sin(angle), kotlin.math.cos(angle)))
                }
            }
            batch.put(p)
            lastU = u; lastV = v
            owner = if (action == "leave") null else id
            if (action == "down") contact = true
            if (action == "up" || action == "leave") contact = false
            if (batch.length() == 128) flush()
        }
        flush()
        return true
    }
}
