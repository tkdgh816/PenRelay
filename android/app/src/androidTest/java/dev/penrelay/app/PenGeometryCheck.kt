package dev.penrelay.app

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.abs

internal fun checkPenSurface(context: Context) {
    val samples = mutableListOf<JSONObject>()
    fun surface(key: String? = null, aspect: Boolean = true, calibrate: Boolean = false) =
        PenSurface(context, 200, 100, key, initialAspect = aspect, requestCalibration = calibrate) { batch ->
        for (i in 0 until batch.length()) samples.add(batch.getJSONObject(i))
    }
    var view = surface()
    view.layout(0, 0, 301, 301)
    fun event(action: Int, x: Int, y: Int, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val pos = MotionEvent.PointerCoords().apply { this.x = x.toFloat(); this.y = y.toFloat() }
        val e = MotionEvent.obtain(now, now, action, 1, arrayOf(props), arrayOf(pos), 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(e) } finally { e.recycle() }
    }
    fun tap(x: Int, y: Int) {
        event(MotionEvent.ACTION_DOWN, x, y)
        event(MotionEvent.ACTION_UP, x, y)
    }
    fun expectPoint(x: Int, y: Int, u: Double, v: Double) {
        samples.clear()
        tap(x, y)
        check(samples.map { it.getString("action") } == listOf("down", "up"))
        for (p in samples) {
            check(abs(p.getDouble("u") - u) < .0001 && abs(p.getDouble("v") - v) < .0001)
        }
    }
    event(MotionEvent.ACTION_DOWN, 150, 150, MotionEvent.TOOL_TYPE_FINGER)
    tap(150, 50)
    check(samples.isEmpty())
    expectPoint(0, 75, 0.0, 0.0)
    expectPoint(300, 225, 1.0, 1.0)
    expectPoint(150, 150, .5, .5)
    samples.clear()
    event(MotionEvent.ACTION_DOWN, 150, 150)
    event(MotionEvent.ACTION_MOVE, 150, 74)
    event(MotionEvent.ACTION_MOVE, 150, 150)
    event(MotionEvent.ACTION_UP, 150, 150)
    check(samples.map { it.getString("action") } == listOf("down", "cancel"))
    view.rotate()
    expectPoint(75, 0, 1.0, 0.0)
    view.toggleAspect()
    expectPoint(0, 300, 0.0, 0.0)
    repeat(3) { view.rotate() }
    view.calibrate()
    samples.clear()
    tap(39, 36); tap(279, 36); tap(39, 276)
    check(samples.isEmpty())
    expectPoint(159, 156, .5, .5)
    view.rotate()
    expectPoint(0, 0, 1.0, 0.0)
    val key = "p5-geometry-trial"
    try {
        view = surface(key, aspect = false, calibrate = true)
        view.layout(0, 0, 301, 301)
        samples.clear()
        tap(39, 36); tap(279, 36); tap(39, 276)
        check(samples.isEmpty())
        view = surface(key, aspect = false)
        view.layout(0, 0, 301, 301)
        expectPoint(159, 156, .5, .5)
        view = surface(key + "-other", aspect = false)
        view.layout(0, 0, 301, 301)
        expectPoint(159, 156, .53, .52)
    } finally {
        val prefs = context.getSharedPreferences("pen-calibration", Context.MODE_PRIVATE)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(key) }.forEach { remove(it) } }.commit()
    }
}
