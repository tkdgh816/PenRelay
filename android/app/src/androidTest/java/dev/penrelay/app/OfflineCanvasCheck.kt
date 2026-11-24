package dev.penrelay.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import dev.penrelay.session.AndroidPairing

// 유효한 시험 등록은 유지하고 닫힌 루프백 포트로 재시도하게 한다.
internal fun Instrumentation.checkOfflineCanvas() {
    check(targetContext.packageName.endsWith(".p2lab") && AndroidPairing.registeredHost(targetContext) != null)
    val activity = startActivitySync(Intent(targetContext,ConnectionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConnectionActivity
    val root = activity.findViewById<ViewGroup>(android.R.id.content)
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    fun button(name: String) = views(root).filterIsInstance<Button>().single { it.text.toString().lineSequence().first() == name }
    fun screenBounds(view: View): Rect {
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        return Rect(pos[0],pos[1],pos[0]+view.width,pos[1]+view.height)
    }
    try {
        runOnMainSync {
            button("연결 방식 변경").performClick()
            views(root).filterIsInstance<RadioButton>().single { it.text == dev.penrelay.session.ConnectionPath.Reverse.label }.performClick()
            views(root).filterIsInstance<EditText>().elementAt(1).setText("1")
            button("적용").performClick()
            views(root).filterIsInstance<Button>().firstOrNull { it.isShown && it.text == "연결" }?.performClick()
            button("캔버스").performClick()
        }
        waitForIdleSync()
        lateinit var page: CanvasSurface
        lateinit var bounds: Rect
        var moved = false
        runOnMainSync {
            page = views(root).filterIsInstance<CanvasSurface>().single()
            bounds = screenBounds(page)
            page.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> if (screenBounds(page) != bounds) moved = true }
        }
        val until = SystemClock.elapsedRealtime() + 8500
        while (SystemClock.elapsedRealtime() < until) {
            runOnMainSync {
                check(activity.outbox == null && button("연결 안 됨").isShown)
                check(page.isAttachedToWindow && screenBounds(page) == bounds && !moved) { "재시도 중 필기면 위치 변경" }
                check(views(root).filterIsInstance<TextView>().none { it.isShown && "ms 후 재시도" in it.text })
            }
            SystemClock.sleep(120)
        }
        runOnMainSync { button("연결 안 됨").performClick() }
        waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            if (uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("ms 후 재시도")?.isNotEmpty() == true) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("연결 상세에서 재시도 원인을 찾지 못했습니다.")
    } finally { runOnMainSync { activity.finish() } }
}
