package dev.penrelay.app

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

// 같은 지점에 가한 서로 다른 필압과 지우기·취소 결과를 확정 픽셀에서 비교한다.
internal fun checkCanvasEditor(context: Context) {
    val doc = CanvasDocument(128, 80)
    val view = CanvasSurface(context, doc, { _, _, _, _ -> }, {})
    view.layout(0, 0, 128, 80)
    view.penColor = Color.RED
    view.penWidth = 12f
    view.eraserWidth = 30f
    fun event(action: Int, x: Int, pressure: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val pos = MotionEvent.PointerCoords().apply { this.x = x.toFloat(); y = 40f; this.pressure = pressure }
        val ev = MotionEvent.obtain(now, now, action, 1, arrayOf(props), arrayOf(pos),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(ev) } finally { ev.recycle() }
    }
    fun tap(x: Int, pressure: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        event(MotionEvent.ACTION_DOWN, x, pressure, tool)
        event(MotionEvent.ACTION_UP, x, pressure, tool)
    }
    fun column(x: Int) = doc.snapshot(-1).pixels.filterIndexed { i, _ -> i % doc.width == x }
    fun thickness(x: Int) = column(x).count { Color.alpha(it) > 0 }
    tap(20, .1f)
    tap(50, 1f)
    check(thickness(20) < thickness(50) && column(20)[40] == Color.RED) { "필압별 굵기" }
    view.pressureEnabled = false
    tap(80, .1f); tap(110, 1f)
    check(thickness(80) == thickness(110))
    val before = doc.snapshot(-1)
    view.eraser = true
    event(MotionEvent.ACTION_DOWN, 50, 1f); event(MotionEvent.ACTION_CANCEL, 50, 1f)
    check(before.revision == doc.revision && before.pixels.contentEquals(doc.snapshot(-1).pixels))
    tap(50, .5f)
    check(thickness(50) == 0 && thickness(20) > 0) { "부분 지우개 픽셀" }
    view.eraser = false
    tap(20, .5f, MotionEvent.TOOL_TYPE_ERASER)
    val last = doc.revision
    tap(80, .5f, MotionEvent.TOOL_TYPE_FINGER)
    check(thickness(20) == 0 && doc.revision == last)
    event(MotionEvent.ACTION_DOWN, 80, .5f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
    check(view.temporaryErasing && view.currentPen == PenKind.Basic)
    event(MotionEvent.ACTION_MOVE, 100, .5f)
    check(view.temporaryErasing)
    event(MotionEvent.ACTION_UP, 110, .5f)
    check(thickness(80) == 0 && thickness(110) == 0 && !view.temporaryErasing && view.currentPen == PenKind.Basic)
    val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
    event(MotionEvent.ACTION_DOWN, 80, .5f)
    event(MotionEvent.ACTION_MOVE, 85, .5f, buttons = primary)
    event(MotionEvent.ACTION_MOVE, 90, .5f)
    check(!view.temporaryErasing && view.currentPen == PenKind.Basic)
    event(MotionEvent.ACTION_UP, 95, .5f, buttons = primary)
    event(MotionEvent.ACTION_HOVER_MOVE, 95, 0f)
    check(thickness(85) > 0 && view.currentPen == PenKind.Basic) { "필기 중 누른 버튼은 펜촉을 뗀 뒤에도 전환하지 않음" }
    event(MotionEvent.ACTION_HOVER_MOVE, 80, 0f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
    check(view.currentPen == PenKind.Basic)
    event(MotionEvent.ACTION_HOVER_MOVE, 80, 0f)
    check(view.currentPen == PenKind.Ballpoint) { "옆 버튼을 짧게 눌렀다 떼면 볼펜 전환" }
    tap(80, .5f)
    check(thickness(80) > 0 && view.currentPen == PenKind.Ballpoint)
}
