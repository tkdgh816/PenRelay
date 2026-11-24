package dev.penrelay.app

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import dev.penrelay.session.*
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID

// 시험 APK 전용. 초대 파일과 시험 PC 지문은 시험 앱의 비공개 디렉터리에만 둔다.
class WifiCheck : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle) { super.onCreate(arguments); options = arguments; start() }
    private fun status(value: String) {
        if (options.getString("check") == "p4") {
            check(targetContext.packageName.endsWith(".p2lab"))
            File(targetContext.cacheDir, "p4-result").appendText(value + "\n")
        }
        sendStatus(0, Bundle().apply { putString("p1", value) })
    }
    override fun onStart() {
        try {
            if (options.getString("check") == "canvas-editor") {
                var failure: Throwable? = null
                runOnMainSync { failure = runCatching { checkCanvasEditor(targetContext) }.exceptionOrNull() }
                failure?.let { throw it }
                status("CANVAS-EDITOR")
            } else if (options.getString("check") == "canvas-offline") {
                checkOfflineCanvas()
                status("CANVAS-OFFLINE")
            } else if (options.getString("check") == "pen-geometry") {
                var failure: Throwable? = null
                runOnMainSync { failure = runCatching { checkPenSurface(targetContext) }.exceptionOrNull() }
                failure?.let { throw it }
                status("P2-GEOMETRY")
            } else if (options.getString("check") == "permission") {
                val activity = startActivitySync(Intent(targetContext, ConnectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                check(!LanPermission.allowed(targetContext)) { "권한이 초기화된 API 37 시험 기기가 필요합니다." }
                fun button(label: String) = views(activity.window.decorView).filterIsInstance<android.widget.Button>()
                    .single { it.text == label && it.isShown }
                runOnMainSync {
                    button("연결 방식 변경").performClick()
                    button(dev.penrelay.session.ConnectionPath.Wifi.label).performClick()
                    val fields = views(activity.window.decorView).filterIsInstance<android.widget.EditText>().toList()
                    fields[0].setText("127.0.0.1")
                    fields[2].setText("1")
                    button("적용").performClick()
                }
                fun request() = runOnMainSync {
                    button("수동 등록").performClick()
                    submitInvitation("0".repeat(64),Base64.getEncoder().encodeToString(ByteArray(32)))
                }
                request()
                clickPermission("permission_deny_button")
                awaitUi {
                    val views = ArrayList<View>()
                    activity.window.decorView.findViewsWithText(views, "연결되지 않음", View.FIND_VIEWS_WITH_TEXT)
                    activity.hasWindowFocus() && views.isNotEmpty()
                }
                check(!LanPermission.allowed(targetContext))
                var connects = 0
                var denied = false
                ManagedConnection({ LanPermission.allowed(targetContext) }, { connects++; error("권한 없이 연결") },
                    { denied = it == ConnectionEvent.PermissionRequired }).run()
                check(denied && connects == 0)
                status("PERMISSION-DENIED 0")
                request()
                clickPermission("permission_allow_button")
                awaitUi { LanPermission.allowed(targetContext) }
                status("PERMISSION-GRANTED")
                runOnMainSync { activity.finish() }
            } else if (options.getString("check") in listOf("ui", "p2", "p3", "p4", "p5")) {
                if (options.getString("check") == "p3") {
                    check(targetContext.packageName.endsWith(".p2lab"))
                    PageBook(targetContext).add()
                }
                val activity = startActivitySync(Intent(targetContext, ConnectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val file = File(targetContext.cacheDir, "p1-invitation")
                val invite = try { file.readLines() } finally { file.delete() }
                fun texts(part: String): List<android.widget.TextView> {
                    val views = ArrayList<View>()
                    activity.window.decorView.findViewsWithText(views, part, View.FIND_VIEWS_WITH_TEXT)
                    return views.filterIsInstance<android.widget.TextView>()
                }
                fun click(label: String) { texts(label).filterIsInstance<android.widget.Button>().single { it.text.toString().lineSequence().first() == label }.performClick() }
                val form = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                fun fields(view: View): List<android.widget.EditText> = when (view) {
                    is android.widget.EditText -> listOf(view)
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
                    else -> emptyList()
                }
                runOnMainSync {
                    click("연결 방식 변경")
                    val route = ConnectionPath.valueOf(options.getString("path") ?: "Wifi")
                    texts(route.label).filterIsInstance<android.widget.RadioButton>().single().performClick()
                    val values = listOf(options.getString("host"), options.getString("session"), options.getString("pair"))
                    fields(form).zip(values).forEach { (field, value) -> field.setText(value) }
                    click("적용")
                    click("수동 등록")
                    submitInvitation(invite[0],invite[1])
                }
                var key = ""
                awaitUi {
                    key = texts("PC에서 이 기기 지문").firstOrNull()?.text.toString().substringAfterLast('\n')
                    key.matches(Regex("[0-9A-F]{64}"))
                }
                status("KEY " + key)
                awaitUi { texts("연결됨").any { it.text == "연결됨" } && (activity as ConnectionActivity).outbox != null }
                status("UI-ONLINE")
                if (options.getString("check") == "p5") {
                    check(targetContext.packageName.endsWith(".p2lab"))
                    val automatic = options.getString("source") == "replay"
                    runOnMainSync { click("디지타이저") }
                    PenTrial(this, activity, ::status).desktopTrial(automatic)
                    runOnMainSync { click("캔버스") }
                    CanvasTrial(this, activity, ::status).desktopTrial(automatic)
                }
                if (options.getString("check") == "p4") {
                    runOnMainSync { click("캔버스") }
                    CanvasTrial(this, activity, ::status).benchmark(options.getString("recovery") ?: "none")
                }
                if (options.getString("check") == "p3") {
                    check(targetContext.packageName.endsWith(".p2lab"))
                    runOnMainSync { click("캔버스") }
                    CanvasTrial(this, activity, ::status).run(options.getString("source") == "fixture")
                }
                if (options.getString("check") == "p2") {
                    check(targetContext.packageName.endsWith(".p2lab"))
                    runOnMainSync { click("디지타이저") }
                    PenTrial(this, activity, ::status).run(options.getString("source") == "replay",
                        options.getString("latency")?.toInt() ?: 0) { click(it) }
                }
                runOnMainSync {
                    val workStatus = texts("연결됨").filterIsInstance<android.widget.Button>().singleOrNull { it.text == "연결됨" }
                    if (workStatus == null) click("연결 해제") else {
                        workStatus.performClick()
                        android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { views(it).toList() }
                            .filterIsInstance<android.widget.Button>().single { it.isShown && it.text == "연결 해제" }.performClick()
                    }
                }
                awaitUi { texts("연결되지 않음").any { it.text == "연결되지 않음" } || texts("연결 안 됨").any { it.text == "연결 안 됨" } }
                status("UI-STOP")
                runOnMainSync { activity.finish() }
            } else {
                val keys = AndroidPairing(targetContext.createDeviceProtectedStorageContext())
                val file = File(targetContext.cacheDir, "p1-invitation")
                val invitation = try { file.readLines() } finally { file.delete() }
                val host = options.getString("host")!!
                val endpoint = InetSocketAddress(host, options.getString("session")!!.toInt())
                val pairing = InetSocketAddress(host, options.getString("pair")!!.toInt())
                status("KEY " + keys.deviceFingerprint)
                check(keys.register(pairing, invitation[0], Base64.getDecoder().decode(invitation[1])))
                val ids = mutableSetOf<UUID>()
                var rtts = 0
                var tries = 0
                var authStopped = false
                val runner = ManagedConnection({ LanPermission.allowed(targetContext) },
                    { keys.openSession(endpoint, listOf("Digitizer", "Canvas"), emptyList()) }, {
                        when (it) {
                            is ConnectionEvent.Online -> { check(ids.add(it.outbox.id)); rtts = 0; status("ONLINE " + ids.size) }
                            is ConnectionEvent.RoundTrip -> { rtts++; status("RTT ${ids.size} $rtts ${it.micros}") }
                            is ConnectionEvent.Retry -> { check(++tries <= 5); status("RETRY " + it.delayMillis) }
                            ConnectionEvent.PairingRequired -> { authStopped = true; status("REPAIR") }
                            ConnectionEvent.PermissionRequired -> error("LAN 권한 필요")
                        }
                    })
                runner.use { it.run() }
                check(ids.size == 2 && authStopped && rtts >= 2)
            }
            if (options.getString("check") == "p4") status("PASS")
            finish(Activity.RESULT_OK, Bundle().apply { putString("p1", "PASS") })
        } catch (error: Throwable) {
            if (options.getString("check") == "p4") status("FAIL $error")
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("p1", "FAIL " + error.toString()) })
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun submitInvitation(pin: String, token: String) {
        val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().map { views(it).toList() }
            .single { nodes -> nodes.filterIsInstance<android.widget.Button>().any { it.text == "등록 요청" } }
        val fields = dialog.filterIsInstance<android.widget.EditText>()
        fields[0].setText(pin)
        fields[1].setText(token)
        dialog.filterIsInstance<android.widget.Button>().single { it.text == "등록 요청" }.performClick()
    }
    private fun awaitUi(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("앱 권한 결과 표시 시간 초과")
    }

    private fun clickPermission(id: String) {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName?.endsWith(":id/$id") == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) {
            val button = uiAutomation.rootInActiveWindow?.let(::find)
            if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(100)
        }
        error("권한 창 버튼 미관찰: $id")
    }
}
