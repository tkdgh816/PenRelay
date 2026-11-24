package dev.penrelay.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import android.view.View
import androidx.activity.ComponentActivity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import dev.penrelay.session.*
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

internal class CanvasBookViewModel(app: Application) : AndroidViewModel(app) {
    val book = PageBook(app)
    override fun onCleared() = book.store.close()
}

class ConnectionActivity : ComponentActivity() {
    private val scanner = registerForActivityResult(ScanContract()) { result -> readInvitation(result.contents) }
    private val lanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val resume = pending
        pending = null
        if (granted) resume?.invoke()
        else notice("LAN 권한이 필요합니다. 권한 설정 후 다시 연결해 주세요.")
        refreshStatus()
    }
    private val direct by lazy { WifiDirectLink(this) }
    private var directHost = ""
    private val nearby = android.Manifest.permission.NEARBY_WIFI_DEVICES
    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val resume = pending
        pending = null
        if (granted) resume?.invoke() else notice("주변 기기 권한이 필요합니다. 권한 설정에서 허용해 주세요.")
        refreshStatus()
    }
    private val directScanner = registerForActivityResult(ScanContract()) { result ->
        if (path == ConnectionPath.Direct) {
            val contents = result.contents
            if (contents == null) {
                if (!editor.open) openSettings()
            } else permitted { direct.scanResult(contents, ::onDirectReady, ::directFailure) }
        }
    }
    private fun directPermission() = checkSelfPermission(nearby) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val worker = Executors.newSingleThreadExecutor()
    private val closer = Executors.newSingleThreadExecutor()
    private val ui by lazy { PenRelayUi(this) }
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private lateinit var applied: ConnectionSettings
    private val path get() = applied.path
    private lateinit var editor: RouteEditor
    private lateinit var settingsToggle: Button
    private lateinit var connectToggle: Button
    private var workStatus: Button? = null
    private lateinit var status: TextView
    private lateinit var statusInfo: TextView
    private lateinit var modeStatus: TextView
    private var registeredPc: String? = null
    private var pairingNeeded = false
    private var registeringAt: ConnectionSettings? = null
    private val pairing get() = registeringAt != null
    private var registered = false
    private var disconnectedByUser: Boolean
        get() = prefs.getBoolean("disconnected-by-user",false)
        set(value) { prefs.edit().putBoolean("disconnected-by-user",value).apply() }
    private var pending: (() -> Unit)? = null
    @Volatile private var generation = 0
    @Volatile private var connection: ManagedConnection? = null
    private var cancellation: CancellationSignal? = null
    private lateinit var form: ScrollView
    @Volatile internal var outbox: SessionOutbox? = null
        private set
    internal var onRoundTrip: ((Long) -> Unit)? = null
    private var desired: TransportMode? = null
    private var surface: PenSurface? = null
    private var canvasView: CanvasSurface? = null
    private var publisher: CanvasPublisher? = null
    private var canvasPageSelector: Button? = null
    private var undoButton: ImageButton? = null
    private var redoButton: ImageButton? = null
    private lateinit var edges: WindowInsetsLayout
    private var notesOpen = false
    private var filmstripOpen = false
    private var filmstrip: CanvasStrip? = null
    private fun showContent(view: View) {
        edges.removeAllViews()
        edges.addView(view)
    }
    internal var canvasAck = ""
        private set
    internal var onCanvasAck: ((Long,String) -> Unit)? = null
    private var canvasNotice = ""
    private var statusDialog: AlertDialog? = null
    private var lastFailure = ""
    private val book by lazy { ViewModelProvider(this)[CanvasBookViewModel::class.java].book }
    private val document get() = book.document
    private var bookPublisher: BookPublisher? = null
    private var syncStatus = ""
    private var connectionLabel = "연결 대기 중"
    private val pageActions = PageActions(this,{ book },::selectPage,{
        updateCanvasPageTitle()
        filmstrip?.refresh()
        bookChanged(deferEdits = false)
    }) {
        Toast.makeText(this,it,Toast.LENGTH_SHORT).show()
    }
    private fun updateCanvasPageTitle() {
        canvasPageSelector?.let { button ->
            val title = book.title(book.selected)
            button.contentDescription = "노트 선택 · $title"
            button.tooltipText = "$title · 길게 눌러 새 노트 추가"
        }
    }
    private fun bookChanged(deferEdits: Boolean = true) {
        updateHistoryButtons()
        canvasView?.invalidate()
        filmstrip?.findViewWithTag<View>(document.id)?.invalidate()
        publisher?.notifyOverlayEdit()
        bookPublisher?.changed(deferEdits)
        publisher?.changed()
        updateCanvasStatus()
    }
    private var restartCanvasMode = false
    private fun selectPage(page: CanvasInfo) {
        book.select(page)
        bookPublisher?.selectionChanged()
        if (canvasView == null) return
        val live = publisher != null || restartCanvasMode
        canvasView?.cancelStroke()
        clearPublisher()
        canvasView = null
        showCanvas()
        val queue = outbox
        if (live && queue != null) {
            restartCanvasMode = true
            fun request() {
                if (outbox !== queue || desired != TransportMode.Canvas || book.selected !== page) return
                when (queue.selectMode(TransportMode.Canvas)) {
                    OfferResult.BUSY -> canvasView?.postDelayed({ request() },32)
                    else -> restartCanvasMode = false
                }
            }
            request()
        }
    }
    private fun returnHome() {
        if (surface != null) stop() else { desired = null; showForm() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageActions.exportId = savedInstanceState?.getString("export-id")
        pageActions.exportRevision = savedInstanceState?.getLong("export-revision", -1) ?: -1
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (surface != null || canvasView != null) returnHome()
                else if (editor.open) finishEditing {}
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val home = ui.column().apply { ui.inset(this,18,14) }
        form = ScrollView(this).apply { setBackgroundColor(ui.paper); addView(home) }
        edges = WindowInsetsLayout(this)
        setContentView(edges)
        showContent(form)
        notesOpen = savedInstanceState?.getBoolean("notes-open") ?: false
        filmstripOpen = savedInstanceState?.getBoolean("filmstrip") ?: false
        // 모든 모드가 같은 Activity 콘텐츠 루트를 쓰므로 전환·회전 때에도 버튼을 시스템 바 아래에 둔다.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(lightBars,lightBars)
        edges.requestApplyInsets()
        val hostKey = AndroidPairing.registeredHost(this)
        registered = hostKey != null
        applied = ConnectionSettings.load(prefs,hostKey)
        registeredPc = prefs.getString("pc-name",null)
        home.addView(ui.hero())
        val body = LinearLayout(this)
        val wide = resources.configuration.screenWidthDp >= 760
        body.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        home.addView(body)
        val tasks = ui.column()
        val setup = ui.column()
        for (panel in listOf(tasks, setup)) {
            panel.background = ui.shape(android.graphics.Color.WHITE,24)
            ui.inset(panel, 16, 14)
            val params = if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
            params.setMargins(if (wide && panel === setup) ui.dp(12) else 0,ui.dp(12),0,0)
            body.addView(panel, params)
        }
        fun heading(panel: LinearLayout, title: String) {
            if (panel.childCount > 0) {
                val divider = View(this).apply { setBackgroundColor(0xffdedbe6.toInt()) }
                panel.addView(divider, LinearLayout.LayoutParams(-1, ui.dp(1)).apply {
                    topMargin = ui.dp(20)
                    bottomMargin = ui.dp(20)
                })
            }
            panel.addView(ui.label(title, 22, ui.ink, true).apply { isAccessibilityHeading = true },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        }
        heading(tasks, "작업 선택")
        tasks.addView(ui.mode("캔버스", "캔버스에 쓰기 · 오프라인에서도 필기", R.drawable.penrelay_nib) { finishEditing { select(TransportMode.Canvas) } })
        tasks.addView(ui.mode("디지타이저", "PC에 펜 입력 · 선택한 모니터에서 필기", R.drawable.penrelay_monitor) { finishEditing { select(TransportMode.Digitizer) } })
        heading(setup, "PC 연결")
        status = ui.label("",18,ui.ink,true).also(setup::addView)
        statusInfo = ui.label("").also(setup::addView)
        modeStatus = ui.label("").also(setup::addView)
        connectToggle = ui.action("연결",::toggleConnection).also(setup::addView)
        editor = RouteEditor(ui,prefs,{ beginDirectJoin(scan = true) },{ beginDirectJoin(scan = false) }) { applyEditor() }
        settingsToggle = ui.action("연결 방식 변경",::openSettings).also(setup::addView)
        setup.addView(editor.panel)
        heading(setup, "프로필")
        setup.addView(ConnectionProfiles(ui, ConnectionLibrary(prefs), ::settingsForProfile) { profile ->
            editor.show(profile)
            updateEditorToggle()
            settingsToggle.post {
                settingsToggle.requestRectangleOnScreen(android.graphics.Rect(0, 0, settingsToggle.width, settingsToggle.height))
            }
        }.actions)
        heading(setup, "PC 등록")
        setup.addView(ui.label("PC에서 ‘기기 초대’를 연 뒤 QR 코드를 스캔하세요."))
        val registration = ui.row()
        registration.addView(ui.action("PC 초대 QR 스캔") { finishEditing(::scanInvitation) })
        registration.addView(ui.action("수동 등록") { finishEditing(::manualRegistration) })
        setup.addView(ui.rail(registration))
        setup.addView(ui.action("권한 설정") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        savedInstanceState?.getBundle("route-draft")?.let { editor.show(applied,it); updateEditorToggle() }
        connectionLabel = ""
        refreshStatus()
        if (savedInstanceState?.getBoolean("canvas-open") == true) {
            desired = TransportMode.Canvas
            showCanvas()
        }
        if (registered && !disconnectedByUser && (if (path == ConnectionPath.Direct) direct.connected else applied.host.isNotBlank()) &&
            (path == ConnectionPath.Reverse || LanPermission.allowed(this))) start(keepCanvas = canvasView != null)
    }

    private fun scanInvitation() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("PC에서 ‘기기 초대’를 누르고 QR 코드를 비추세요.")
            .setBeepEnabled(false).setBarcodeImageEnabled(false))
    }

    private fun readInvitation(scanned: String?) {
        val contents = scanned ?: run {
            notice("스캔을 마치지 못했습니다. 카메라 권한을 확인하거나 ‘연결 방식 변경’을 사용하세요.")
            return
        }
        try {
            require(contents.length <= 1024)
            val invite = JSONObject(contents)
            require(invite.getString("type") == "penrelay-invite-1")
            val route = ConnectionPath.valueOf(invite.getString("route"))
            val host = invite.getString("host")
            require(android.net.InetAddresses.isNumericAddress(host) && route.host(host) == host)
            fun port(key: String, default: Int): Int {
                if (!invite.has(key)) return default
                return requireNotNull(invite.get(key).toString().toIntOrNull()?.takeIf { it in 1..65535 })
            }
            val invited = ConnectionSettings(route,host,port("port",47120),port("pairingPort",47121))
            require(invited.port != invited.pairingPort)
            val fingerprint = invite.getString("pin")
            require(fingerprint.matches(Regex("[A-F0-9]{64}")))
            val token = invite.getString("token")
            require(Base64.getDecoder().decode(token).size == 32)
            val name = invite.getString("pc").take(64)
            AlertDialog.Builder(this).setTitle("이 PC를 등록할까요?")
                .setMessage("$name · $host\n${route.label} · 세션 ${invited.port} · 등록 ${invited.pairingPort}\nPC 지문 $fingerprint")
                .setNegativeButton("취소", null).setPositiveButton("등록 요청") { _, _ ->
                    requestRegistration(invited,fingerprint,token,name)
                }.show()
        } catch (_: Exception) { notice("이 앱의 PC 초대 코드를 읽을 수 없습니다. PC에서 새 초대를 열고 다시 스캔하세요.") }
    }

    private fun manualRegistration() {
        if (applied.host.isBlank() && path != ConnectionPath.Direct) {
            notice("먼저 연결 방식을 선택하고 PC 주소를 적용하세요.")
            openSettings()
            return
        }
        val fields = ui.column().apply { ui.inset(this,20,8) }
        fun field(label: String, hidden: Boolean): EditText {
            fields.addView(ui.label(label))
            return EditText(this).apply {
                setSingleLine()
                isSaveEnabled = false
                if (hidden) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                fields.addView(this)
            }
        }
        val pin = field("PC 공개키 지문",false)
        val secret = field("일회용 비밀",true)
        val dialog = AlertDialog.Builder(this).setTitle("PC 수동 등록")
            .setMessage("${applied.path.label} · ${applied.host} · 등록 포트 ${applied.pairingPort}")
            .setView(fields).setNegativeButton("취소",null).setPositiveButton("등록 요청",null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val fingerprint = pin.text.toString().trim().uppercase()
            val token = secret.text.toString().trim()
            when {
                !fingerprint.matches(Regex("[A-F0-9]{64}")) -> pin.error = "PC 초대 화면의 지문을 확인하세요."
                runCatching { Base64.getDecoder().decode(token).size }.getOrNull() != 32 -> secret.error = "PC 초대 화면의 일회용 비밀을 확인하세요."
                else -> { dialog.dismiss(); requestRegistration(applied,fingerprint,token,applied.host) }
            }
        }
    }
    private data class Invitation(val fingerprint: String,val token: String,val pc: String)
    private fun requestRegistration(endpoint: ConnectionSettings, fingerprint: String, token: String, name: String) {
        permitted(endpoint.path) {
            disconnectedByUser = false
            start(endpoint = endpoint, invitation = Invitation(fingerprint,token,name))
        }
    }
    private fun updateEditorToggle() {
        settingsToggle.text = if (editor.open) "연결 방식 접기" else "연결 방식 변경"
    }
    private fun closeEditor() {
        editor.panel.visibility = View.GONE
        updateEditorToggle()
    }
    private fun settingsForProfile(): ConnectionSettings? {
        if (!editor.open) {
            if (path == ConnectionPath.Direct || applied.host.isNotBlank()) return applied
            openSettings()
        }
        return editor.read()
    }
    private fun openSettings() {
        if (editor.open) { finishEditing {}; return }
        desired = null
        showForm()
        editor.show(applied)
        updateEditorToggle()
    }
    private fun beginDirectJoin(scan: Boolean) {
        val next = editor.read() ?: return
        if (next.path != ConnectionPath.Direct) return
        stop(keepCanvas = canvasView != null)
        applied = next
        next.save(prefs)
        closeEditor()
        permitted(ConnectionPath.Direct) {
            if (scan) {
                directScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("PC 대시보드의 Wi-Fi Direct 연결 QR을 비추세요.")
                    .setBeepEnabled(false).setBarcodeImageEnabled(false))
            } else direct.askCode(::onDirectReady, ::directFailure)
        }
    }
    private fun onDirectReady() {
        disconnectedByUser = false
        if (registered && !pairingNeeded) start(keepCanvas = canvasView != null)
        else notice("무선 연결 완료. PC에서 기기 초대를 열고 등록 QR을 스캔하세요.")
    }
    private fun directFailure(message: String) {
        if (!editor.open) openSettings()
        notice(message)
    }
    private fun applyEditor(after: () -> Unit = {}) {
        if (!editor.changed(applied)) { closeEditor(); after(); return }
        val next = editor.read() ?: return
        val reconnect = cancellation != null && !pairing &&
            (next.path != path || next.host != applied.host || next.port != applied.port)
        val mode = desired
        if (reconnect || pairing) stop(keepCanvas = canvasView != null)
        if (path == ConnectionPath.Direct && next.path != path) direct.release()
        applied = next
        ConnectionLibrary(prefs).remember(next)
        next.save(prefs)
        closeEditor()
        connectionLabel = ""
        lastFailure = ""
        canvasNotice = ""
        refreshStatus()
        if (reconnect) permitted {
            val resumeMode = desired ?: mode
            start(keepCanvas = canvasView != null)
            desired = resumeMode
        }
        after()
    }
    private fun finishEditing(after: () -> Unit) {
        if (!editor.open) { after(); return }
        if (!editor.changed(applied)) { closeEditor(); after(); return }
        AlertDialog.Builder(this).setTitle("연결 방식 변경을 적용할까요?")
            .setMessage("적용하면 새 연결 설정을 사용합니다. 현재 연결 중이면 변경한 주소와 방식으로 다시 연결합니다.")
            .setPositiveButton("적용") { _,_ -> applyEditor(after) }
            .setNegativeButton("변경 버리기") { _,_ -> closeEditor(); after() }
            .setNeutralButton("계속 편집",null).show()
    }
    private fun toggleConnection() {
        if (cancellation != null || outbox != null || pending != null) {
            disconnectedByUser = true
            if (path == ConnectionPath.Direct) direct.release()
            stop(keepCanvas = canvasView != null)
            return
        }
        finishEditing {
            when {
                path == ConnectionPath.Direct && !direct.connected -> openSettings()
                !registered || pairingNeeded -> AlertDialog.Builder(this)
                    .setTitle(if (registered) "PC를 다시 등록해 주세요" else "연결할 PC를 등록해 주세요")
                    .setMessage("PC에서 ‘기기 초대’를 연 뒤 QR 코드를 스캔하거나 초대 정보를 직접 입력하세요.")
                    .setPositiveButton("QR 스캔") { _,_ -> scanInvitation() }
                    .setNeutralButton("수동 등록") { _,_ -> manualRegistration() }
                    .setNegativeButton("취소",null).show()
                applied.host.isBlank() && path != ConnectionPath.Direct -> openSettings()
                else -> permitted { disconnectedByUser = false; start(keepCanvas = canvasView != null) }
            }
        }
    }
    private fun failure(message: String, disconnected: Boolean = false) {
        connectionLabel = if (disconnected) "연결 끊김" else "연결 실패"
        lastFailure = message
        refreshStatus()
    }
    private fun select(mode: TransportMode) {
        val queue = outbox
        if (mode == TransportMode.Canvas && queue == null) {
            desired = mode
            showCanvas()
            if (registered && !disconnectedByUser && cancellation == null && pending == null &&
                (path != ConnectionPath.Direct || direct.connected) &&
                (path == ConnectionPath.Reverse || LanPermission.allowed(this))) start(keepCanvas = true)
            return
        }
        if (queue == null) {
            if (registered && (cancellation != null || pending != null)) { desired = mode; return }
            if (path == ConnectionPath.Direct && !direct.connected) { openSettings(); return }
            if (registered) permitted { disconnectedByUser = false; start(); desired = mode }
            else notice("PC에서 기기를 초대하고 이 태블릿을 등록한 뒤 연결하세요.")
            return
        }
        when (queue.selectMode(mode)) {
            OfferResult.ACCEPTED -> {
                desired = mode
                clearPublisher()
                if (mode == TransportMode.Canvas) showCanvas() else showForm()
                modeStatus.text = "모드 전환 중"
            }
            OfferResult.BUSY -> { stop(); notice("송신 대기열이 가득 차 연결을 중지했습니다.") }
            else -> notice("연결 또는 모드 승인을 기다려 주세요.")
        }
    }
    private fun showForm() {
        if (surface != null || canvasView != null) {
            surface = null
            canvasView = null
            workStatus = null
            canvasPageSelector = null
            undoButton = null
            redoButton = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            filmstrip = null
            showContent(form)
        }
    }
    private fun clearPublisher() {
        publisher?.close()
        publisher = null
        canvasAck = ""
        updateCanvasStatus()
    }
    private val connectionSummary get() = when {
        outbox != null -> "연결됨"
        pending != null -> "권한 요청 중"
        pairing -> "PC 등록 중"
        cancellation != null -> "연결 시도 중"
        else -> "연결되지 않음"
    }
    private val actionLabel get() = when {
        outbox != null -> "연결 해제"
        cancellation != null || pending != null -> "연결 취소"
        else -> "연결"
    }
    private fun connectionInfo(): String {
        val endpoint = registeringAt ?: applied
        val addressLabel = when (endpoint.path) {
            ConnectionPath.Direct -> "Wi-Fi Direct PC 주소"
            ConnectionPath.Wifi -> "Wi-Fi PC 주소"
            ConnectionPath.Reverse -> "ADB 루프백 주소"
            ConnectionPath.Tether -> "USB 테더링 PC 주소"
        }
        val pc = if (registered) registeredPc ?: "등록된 PC" else "없음"
        return listOf("연결 방식 · ${endpoint.path.label}", "등록된 PC · $pc",
            "$addressLabel · ${if (endpoint.path == ConnectionPath.Direct) directHost.ifBlank { "그룹 연결 시 확인" } else endpoint.host.ifBlank { "미설정" }}",
            "세션 포트 · ${endpoint.port}     등록 포트 · ${endpoint.pairingPort}",
            connectionLabel,lastFailure,syncStatus,canvasAck,canvasNotice).filter { it.isNotBlank() }.distinct().joinToString("\n")
    }
    private fun showConnectionDetails() {
        statusDialog = AlertDialog.Builder(this).setTitle(connectionSummary).setMessage(connectionInfo())
            .setPositiveButton("닫기",null)
            .setNegativeButton("연결 방식 변경") { _,_ -> openSettings() }
            .setNeutralButton(actionLabel) { _,_ -> toggleConnection() }
            .show().apply { setOnDismissListener { if (statusDialog === this) statusDialog = null } }
    }
    private val workStatusText get() = if (outbox != null) "연결됨" else "연결 안 됨"
    private fun workConnectionButton() = ui.toolbarAction(workStatusText,::showConnectionDetails).apply {
        setSingleLine()
        ui.inset(this,8,4)
        minWidth = kotlin.math.ceil(paint.measureText("연결 안 됨")).toInt() + paddingLeft + paddingRight
        tooltipText = "연결 정보"
        workStatus = this
    }
    private fun workCommands(sidebar: Boolean, showMenu: (View) -> Unit) = ui.commandGroup(sidebar).apply {
        addView(workConnectionButton())
        addView(ui.toolbarAction("메뉴") {}.apply { setOnClickListener { showMenu(it) } })
    }
    private fun updateCanvasStatus() {
        val summary = connectionSummary
        if (status.text != summary) status.text = summary
        workStatus?.let { if (it.text != workStatusText) it.text = workStatusText }
        val info = connectionInfo()
        if (statusInfo.text != info) statusInfo.text = info
        if (connectToggle.text != actionLabel) connectToggle.text = actionLabel
        statusDialog?.let {
            it.setTitle(summary); it.setMessage(info)
            it.getButton(AlertDialog.BUTTON_NEUTRAL).text = actionLabel
        }
    }
    private fun refreshStatus() = updateCanvasStatus()
    private fun notice(message: String) {
        canvasNotice = message
        refreshStatus()
    }
    private fun updateHistoryButtons() {
        fun update(button: ImageButton?, enabled: Boolean) {
            button?.isEnabled = enabled
            button?.alpha = if (enabled) 1f else .38f
        }
        update(undoButton,document.canUndo)
        update(redoButton,document.canRedo)
    }
    private fun showCanvas() {
        if (canvasView != null) { updateCanvasStatus(); return }
        surface = null
        val view = CanvasSurface(this, document, { ink, action, points, offset -> publisher?.preview(ink, action, points, offset) },
            { bookChanged() }, { filmstrip?.findViewWithTag<View>(document.id)?.invalidate() },
            { stroke -> publisher?.savedStroke(stroke) },
            { gesture, hits -> publisher?.wholePreview(gesture, hits) })
        canvasView = view
        val tools = getPreferences(MODE_PRIVATE)
        val savedMode = tools.getString("preview-mode", null)
        view.previewMode = when (savedMode) {
            "PenUp", PreviewMode.CompletedFirst.name -> PreviewMode.CompletedFirst
            PreviewMode.Realtime.name -> PreviewMode.Realtime
            else -> if (tools.getBoolean("realtime-preview", true)) PreviewMode.Realtime else PreviewMode.CompletedFirst
        }
        if (savedMode != view.previewMode.name) {
            tools.edit().putString("preview-mode", view.previewMode.name).apply()
        }
        val cycle = tools.getStringSet("cycle-pens",null)
        view.cyclePens = PenKind.entries.filter { cycle == null || it.name in cycle }.toSet()
            .ifEmpty { PenKind.entries.toSet() }
        view.penColor = tools.getInt("ink-color", CanvasSurface.INK)
        view.penWidth = tools.getFloat("ink-width", CanvasSurface.BRUSH)
        view.eraserWidth = tools.getFloat("eraser-width",(view.penWidth*3).coerceIn(1f,288f))
        view.lastInk = Brush.entries.firstOrNull { !it.erases && it.name == tools.getString("last-ink","Pen") } ?: Brush.Pen
        view.lastEraser = Brush.entries.firstOrNull { it.erases && it.name == tools.getString("last-eraser","Partial") } ?: Brush.Partial
        view.pressureEnabled = tools.getBoolean("ink-pressure", true)
        view.pressureStrength = tools.getInt("pressure-strength", 100).coerceIn(0, 100)
        view.penTexture = InkTexture.entries.firstOrNull {
            it != InkTexture.Graphite && it.name == tools.getString("pen-texture", null)
        } ?: InkTexture.Smooth
        view.brush = Brush.entries.firstOrNull { it.name == tools.getString("ink-brush","Pen") } ?: Brush.Pen
        fun historyAction(edit: () -> Unit) {
            view.cancelStroke()
            try { edit(); bookChanged() }
            catch (e: java.io.IOException) { Toast.makeText(this,"필기 저장 실패 · ${e.localizedMessage}",Toast.LENGTH_LONG).show() }
        }
        val undo = ui.historyButton("실행 취소",R.drawable.penrelay_undo) { historyAction(view.document::undo) }
        val redo = ui.historyButton("다시 실행",R.drawable.penrelay_redo) { historyAction(view.document::redo) }
        undoButton = undo
        redoButton = redo
        updateHistoryButtons()
        val sidebar = CanvasOrientation.from(resources.configuration) == CanvasOrientation.Landscape
        val toolsView = ui.editor(view, pageActions::clearPage, undo, redo, sidebar) {
            tools.edit().putInt("ink-color", view.penColor).putFloat("ink-width", view.penWidth)
                .putFloat("eraser-width",view.eraserWidth).putString("last-ink",view.lastInk.name).putString("last-eraser",view.lastEraser.name)
                .putInt("pressure-strength",view.pressureStrength).putString("pen-texture",view.penTexture.name)
                .putStringSet("cycle-pens",view.cyclePens.map { it.name }.toSet())
                .putBoolean("ink-pressure", view.pressureEnabled).putString("ink-brush",view.brush.name).apply()
        }
        val pages = ui.commandGroup(sidebar)
        canvasPageSelector = ui.toolbarAction("노트") {}.apply {
            setSingleLine()
            setOnLongClickListener { pageActions.confirmAddNote(); true }
        }.also(pages::addView)
        updateCanvasPageTitle()
        val stripHost = FrameLayout(this)
        val stripButton = ui.toolbarAction("페이지") {}.apply {
            setSingleLine()
            tooltipText = "길게 눌러 새 페이지 추가"
            setOnLongClickListener { pageActions.confirmAddCanvas(); true }
        }.also(pages::addView)
        fun displayStrip() {
            stripHost.removeAllViews()
            stripHost.visibility = if (filmstripOpen || notesOpen) View.VISIBLE else View.GONE
            filmstrip = if (filmstripOpen || notesOpen) CanvasStrip(this,book,{ page ->
                if (notesOpen) {
                    notesOpen = false
                    filmstripOpen = true
                    if (book.selected === page) displayStrip() else selectPage(page)
                } else selectPage(page)
            },pageActions,sidebar,notesOpen) else null
            filmstrip?.let(stripHost::addView)
            for ((button, open) in listOf(stripButton to filmstripOpen, canvasPageSelector!! to notesOpen)) {
                button.isSelected = open
                button.stateDescription = if (open) "펼쳐짐" else "접힘"
                button.setTextColor(if (open) android.graphics.Color.WHITE else ui.violet)
                button.backgroundTintList = if (open) android.content.res.ColorStateList.valueOf(ui.violet) else null
            }
        }
        stripButton.setOnClickListener { notesOpen = false; filmstripOpen = !filmstripOpen; displayStrip() }
        canvasPageSelector?.setOnClickListener { filmstripOpen = false; notesOpen = !notesOpen; displayStrip() }
        displayStrip()
        val commands = workCommands(sidebar) { anchor ->
            pageActions.mainMenu(anchor,view.previewMode,{ mode ->
                view.previewMode = mode
                tools.edit().putString("preview-mode",mode.name).apply()
            },::returnHome) { select(TransportMode.Digitizer) }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showContent(ui.workArea(sidebar,pages,toolsView,commands,view,stripHost))
        updateCanvasStatus()
    }
    private fun modeAck(frame: Frame) {
        val queue = outbox ?: return
        if (queue.id != frame.sessionId) return
        val ack = JSONObject(frame.payload.decodeToString())
        if (ack.getString("mode") == "Canvas") {
            restartCanvasMode = false
            clearPublisher()
            canvasNotice = ""
            val epoch = ack.getString("modeEpoch")
            publisher = CanvasPublisher(document, queue, epoch, ack.optBoolean("liveCanvas", true),
                loadFailed = { message -> runOnUiThread { if (publisher?.epoch == epoch) notice(message) } }) { revision, hash ->
                canvasAck = "PC r$revision · $hash"; onCanvasAck?.invoke(revision,hash); updateCanvasStatus()
            }
            if (desired == TransportMode.Canvas) showCanvas()
            modeStatus.text = "캔버스 모드"
            return
        }
        val w = ack.optInt("width")
        val h = ack.optInt("height")
        if (w <= 0 || h <= 0) { desired = null; notice("PC에서 대상 모니터를 선택하고 디지타이저 호스트를 실행해 주세요."); return }
        val epoch = ack.getString("modeEpoch")
        val mapping = ack.optJSONObject("mapping")
        val key = mapping?.optString("MonitorKey")?.takeIf { it.matches(Regex("[A-F0-9]{64}")) }
        val pen = PenSurface(this, w, h, key, mapping?.optInt("QuarterTurns") ?: 0,
            mapping?.optBoolean("KeepAspect", true) ?: true, mapping?.optBoolean("Calibrate") ?: false) { samples ->
            val payload = JSONObject().put("epoch", epoch).put("samples", samples).toString().encodeToByteArray()
            if (queue.offer(FrameKind.PEN_BATCH, payload) == OfferResult.BUSY) {
                stop(); notice("송신 지연으로 접촉을 종료했습니다. 다시 연결해 주세요.")
            }
        }
        showForm()
        surface = pen
        modeStatus.text = "디지타이저 모드"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sidebar = CanvasOrientation.from(resources.configuration) == CanvasOrientation.Landscape
        val monitor = mapping?.optString("MonitorName").orEmpty().ifBlank { "PC 선택 모니터" }
        val target = ui.column().apply {
            ui.inset(this,6,4)
            addView(ui.label(monitor,14,ui.ink,true).apply {
                maxWidth = ui.dp(180); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(ui.label("${w}×$h"))
        }
        val tools = ui.commandGroup(sidebar).apply {
            background = ui.shape(android.graphics.Color.WHITE,8)
            addView(ui.action("90° 회전",pen::rotate))
            addView(ui.action("비율 전환",pen::toggleAspect))
            addView(ui.action("세 점 보정",pen::calibrate))
        }
        val commands = workCommands(sidebar) { anchor ->
            PopupMenu(this,anchor).apply {
                menu.add("홈").setOnMenuItemClickListener { returnHome(); true }
                menu.add("캔버스 모드").setOnMenuItemClickListener { select(TransportMode.Canvas); true }
            }.show()
        }
        showContent(ui.workArea(sidebar,target,tools,commands,pen))
    }
    private fun permitted(route: ConnectionPath = path, action: () -> Unit) {
        if (route == ConnectionPath.Direct && !directPermission()) {
            if (pending == null) { pending = { permitted(route, action) }; refreshStatus(); nearbyPermission.launch(nearby) }
        } else if (route == ConnectionPath.Reverse || LanPermission.allowed(this)) action()
        else if (pending == null) { pending = action; refreshStatus(); lanPermission.launch(LanPermission.NAME) }
    }
    private fun stop(keepCanvas: Boolean = false) {
        generation++
        directHost = ""
        connectionLabel = ""
        lastFailure = ""
        bookPublisher?.close(); bookPublisher = null; syncStatus = ""
        clearPublisher()
        canvasNotice = ""
        outbox = null
        desired = if (keepCanvas) TransportMode.Canvas else null
        modeStatus.text = ""
        if (!keepCanvas) showForm()
        pending = null
        registeringAt = null
        val oldConnection = connection
        val oldSignal = cancellation
        connection = null
        cancellation = null
        refreshStatus()
        if (oldConnection != null || oldSignal != null) closer.execute {
            try { oldConnection?.close() } finally { oldSignal?.cancel() }
        }
    }
    private fun start(keepCanvas: Boolean = false, endpoint: ConnectionSettings = applied, invitation: Invitation? = null) {
        stop(keepCanvas)
        val current = generation
        val route = endpoint.path
        val host = endpoint.host
        val p = if (invitation != null) endpoint.pairingPort else endpoint.port
        if ((route != ConnectionPath.Direct && host.isBlank()) || p !in 1..65535) { failure("연결 방식 변경에서 PC 주소와 포트를 확인해 주세요."); return }
        registeringAt = if (invitation != null) endpoint else null
        val signal = CancellationSignal().also { cancellation = it }
        refreshStatus()
        worker.execute {
            if (current != generation) return@execute
            try {
                var link: DirectEndpoint? = null
                val keys = if (route == ConnectionPath.Direct) AndroidPairing(this, signal, route) { socket, peer ->
                    requireNotNull(link).bind(socket, peer)
                } else AndroidPairing(this, signal, route)
                fun remote(): InetSocketAddress {
                    if (route != ConnectionPath.Direct) return InetSocketAddress(host, p)
                    val currentLink = direct.endpoint()
                    link = currentLink
                    runOnUiThread { if (current == generation) directHost = currentLink.owner.hostAddress.orEmpty() }
                    return InetSocketAddress(currentLink.owner, p)
                }
                if (invitation != null) {
                    val fingerprint = keys.deviceFingerprint
                    runOnUiThread { if (current == generation) modeStatus.text = "PC에서 이 기기 지문을 확인해 주세요.\n$fingerprint" }
                    val accepted = keys.register(remote(), invitation.fingerprint, Base64.getDecoder().decode(invitation.token))
                    runOnUiThread { if (generation == current) {
                        if (accepted) {
                            registered = true
                            registeredPc = invitation.pc
                            pairingNeeded = false
                            prefs.edit().putString("pc-name",invitation.pc).apply()
                            applied = if (route == ConnectionPath.Direct) endpoint.copy(host = "") else endpoint
                            ConnectionLibrary(prefs).remember(applied)
                            applied.save(prefs)
                            start(keepCanvas = canvasView != null)
                        } else failure("등록 거부. PC에서 새 초대를 열어 주세요.")
                    } }
                } else {
                    val runner = ManagedConnection({ route == ConnectionPath.Reverse || LanPermission.allowed(this) },
                        {
                            runOnUiThread { if (current == generation) {
                                connectionLabel = ""
                                refreshStatus()
                            } }
                            keys.openSession(remote(), listOf("Canvas", "Digitizer"), listOf("stylus", "optionalAxes", "pages-v1", "canvas-visibility-v1", "stroke-sync-v1"))
                        },
                        { event -> runOnUiThread { if (current == generation) {
                            val wasOnline = outbox != null
                            if (event is ConnectionEvent.Online) {
                                ConnectionLibrary(prefs).remember(endpoint)
                                event.pc?.let { registeredPc = it; prefs.edit().putString("pc-name",it).apply() }
                                pairingNeeded = false
                                canvasNotice = ""
                                lastFailure = ""
                                outbox = event.outbox
                                desired?.let { event.outbox.selectMode(it) }
                            } else if (event !is ConnectionEvent.RoundTrip) {
                                bookPublisher?.close(); bookPublisher = null; syncStatus = ""
                                outbox = null; restartCanvasMode = false; clearPublisher()
                                if (desired != TransportMode.Canvas) showForm()
                            }
                            if (event is ConnectionEvent.RoundTrip) onRoundTrip?.invoke(event.micros)
                            val message = when (event) {
                            is ConnectionEvent.Online -> ""
                            is ConnectionEvent.RoundTrip -> connectionLabel
                            is ConnectionEvent.Retry -> {
                                val hint = when (route) {
                                    ConnectionPath.Reverse -> " · 케이블과 PC의 USB 포트 도구를 확인해 주세요."
                                    ConnectionPath.Tether -> " · USB 테더링과 PC의 USB 주소를 확인해 주세요."
                                    ConnectionPath.Direct -> " · 무선 그룹이 끊겼으면 연결 방식 변경에서 PC 연결 QR을 다시 스캔하거나 코드를 입력하세요."
                                    ConnectionPath.Wifi -> " · 두 기기가 같은 Wi-Fi인지, PC의 연결 대기와 방화벽 허용 여부를 확인하세요."
                                }
                                "${event.reason} · ${event.delayMillis} ms 후 재시도$hint"
                            }
                            ConnectionEvent.PermissionRequired -> "LAN 권한 설정 후 다시 연결해 주세요."
                            ConnectionEvent.PairingRequired -> { pairingNeeded = true; "PC에서 새 초대를 열고 태블릿으로 스캔하세요." }
                            }
                            if (event is ConnectionEvent.Retry || event == ConnectionEvent.PermissionRequired || event == ConnectionEvent.PairingRequired) failure(message,wasOnline)
                            else if (event is ConnectionEvent.Online) { connectionLabel = message; refreshStatus() }
                        } } }, { frame -> runOnUiThread {
                            if (current == generation && outbox?.id == frame.sessionId) when (frame.kind) {
                                FrameKind.PAGE_REPLY -> {
                                    val reply = JSONObject(frame.payload.decodeToString())
                                    if (reply.has("liveCanvas")) {
                                        publisher?.takeIf { it.epoch == reply.optString("epoch") }
                                            ?.setVisible(reply.getBoolean("liveCanvas"))
                                    } else if (reply.optBoolean("ready")) {
                                        bookPublisher?.close()
                                        bookPublisher = BookPublisher(book,outbox!!,reply.optBoolean("ink1"),reply.optBoolean("navigation"),reply.optBoolean("ink2"),reply.optJSONObject("strokeSync"),
                                            reconnect = { closer.execute { connection?.retrySession(frame.sessionId) } }) { syncStatus = it; refreshStatus() }
                                        bookPublisher?.changed()
                                    } else if (reply.has("select")) {
                                        val target = book.documents().firstOrNull { it.id == reply.optString("select") }
                                        if (target != null && target !== book.selected) selectPage(target)
                                        else bookPublisher?.selectionChanged()
                                    } else bookPublisher?.reply(reply)
                                }
                                FrameKind.MODE_ACK -> modeAck(frame)
                                FrameKind.CANVAS_ACK, FrameKind.REQUEST_SNAPSHOT -> {
                                    val body = try { JSONObject(frame.payload.decodeToString()) }
                                    catch (_: org.json.JSONException) {
                                        stop(keepCanvas = canvasView != null)
                                        notice("PC의 캔버스 응답을 읽을 수 없어 연결을 중지했습니다.")
                                        return@runOnUiThread
                                    }
                                    if (publisher?.epoch == body.optString("epoch")) {
                                        publisher?.reply(frame.kind, body)
                                        updateCanvasStatus()
                                    }
                                }
                                else -> Unit
                            }
                        } })
                    connection = runner
                    if (current == generation) runner.run() else runner.close()
                }
            } catch (error: Exception) { runOnUiThread { if (current == generation) failure("연결 실패 · PC의 대기 주소를 확인하세요.\n${error.localizedMessage}") } }
            finally { runOnUiThread { if (current == generation) {
                cancellation = null
                connection = null
                registeringAt = null
                modeStatus.text = ""
                refreshStatus()
            } } }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("notes-open",notesOpen)
        outState.putBoolean("filmstrip",filmstripOpen)
        if (editor.open) outState.putBundle("route-draft",editor.snapshot())
        outState.putString("export-id",pageActions.exportId)
        outState.putLong("export-revision",pageActions.exportRevision)
        outState.putBoolean("canvas-open", canvasView != null)
    }
    override fun onPause() {
        if (desired == TransportMode.Canvas) canvasView?.cancelStroke()
        else if (desired != null) stop()
        super.onPause()
    }
    override fun onDestroy() { direct.release(); stop(); worker.shutdownNow(); closer.shutdown(); pageActions.close(); super.onDestroy() }
}
