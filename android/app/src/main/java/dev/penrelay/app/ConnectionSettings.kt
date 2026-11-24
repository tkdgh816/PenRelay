package dev.penrelay.app

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.penrelay.session.ConnectionPath

internal data class ConnectionSettings(val path: ConnectionPath, val host: String, val port: Int, val pairingPort: Int) {
    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("applied-route",path.name).putString("applied-host",host)
            .putInt("applied-port",port).putInt("applied-pair-port",pairingPort)
            .putString(addressKey(path),host).apply()
    }
    companion object {
        fun addressKey(path: ConnectionPath) = when (path) {
            ConnectionPath.Direct -> "direct-address"
            ConnectionPath.Wifi -> "address"
            ConnectionPath.Tether -> "tether-address"
            ConnectionPath.Reverse -> "reverse-address"
        }
        fun load(prefs: SharedPreferences, registeredKey: String?): ConnectionSettings {
            val invitation = registeredKey != null && prefs.getString("invite-host-key",null) == registeredKey &&
                prefs.getString("last-host-key",null) != registeredKey
            val previous = if (invitation) "invite-route" else "last-route"
            val name = prefs.getString("applied-route",prefs.getString(previous,prefs.getString("path",null)))
            val path = ConnectionPath.entries.firstOrNull { it.name == name } ?: ConnectionPath.Wifi
            val host = prefs.getString("applied-host",prefs.getString(if (invitation) "invite-address" else addressKey(path),"")).orEmpty()
            val oldPort = prefs.getString(if (invitation) "invite-port" else "last-port",prefs.getString("port","47120"))?.toIntOrNull()
            return ConnectionSettings(path,path.host(host),prefs.getInt("applied-port",oldPort ?: 47120),prefs.getInt("applied-pair-port",47121))
        }
    }
}

internal class RouteEditor(private val ui: PenRelayUi, private val prefs: SharedPreferences, scanCode: () -> Unit, enterCode: () -> Unit, apply: () -> Unit) {
    val panel = ui.column()
    private val library = ConnectionLibrary(prefs)
    private val choices = RadioGroup(ui.context).apply { isSaveFromParentEnabled = false }
    private val routes = ConnectionPath.entries.associateWith { route ->
        RadioButton(ui.context).apply { id = View.generateViewId(); text = route.label; choices.addView(this) }
    }
    private var route = ConnectionPath.Wifi
    private val draftHosts = mutableMapOf<ConnectionPath,String>()
    private val host: EditText
    private val hostRow = ui.row()
    private val history = ui.action("최근 주소 ▾", ::showHistory)
    private val scan = ui.action("PC 연결 QR 스캔", scanCode)
    private val code = ui.action("PC 연결 코드 입력", enterCode)
    private val port: EditText
    private val pairingPort: EditText
    init {
        panel.visibility = View.GONE
        panel.addView(choices)
        fun field(label: String, numeric: Boolean): EditText {
            val input = EditText(ui.context).apply {
                id = View.generateViewId()
                isSaveEnabled = false
                setSingleLine()
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }
            panel.addView(ui.label(label).apply { labelFor = input.id })
            if (numeric) panel.addView(input) else {
                hostRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                hostRow.addView(history)
                panel.addView(hostRow)
            }
            return input
        }
        host = field("PC 주소",false)
        port = field("세션 포트",true)
        pairingPort = field("등록 포트",true)
        panel.addView(scan)
        panel.addView(code)
        choices.setOnCheckedChangeListener(::routeSelected)
        panel.addView(ui.action("적용",apply))
    }
    private fun routeSelected(group: RadioGroup, id: Int) {
        val next = routes.entries.firstOrNull { it.value.id == id && it.value.isChecked }?.key ?: return
        if (route == next || group.checkedRadioButtonId != id) return
        draftHosts[route] = host.text.toString()
        route = next
        displayHost(draftHosts[next] ?: prefs.getString(ConnectionSettings.addressKey(next),"").orEmpty())
    }

    private fun displayHost(address: String) {
        host.setText(route.host(address))
        host.isEnabled = route == ConnectionPath.Wifi || route == ConnectionPath.Tether
        history.visibility = if (host.isEnabled) View.VISIBLE else View.GONE
        scan.visibility = if (route == ConnectionPath.Direct) View.VISIBLE else View.GONE
        code.visibility = scan.visibility
    }
    private fun showHistory() {
        val rows = ui.column()
        val scroll = ScrollView(ui.context).apply { addView(rows) }
        val popup = PopupWindow(scroll, hostRow.width, -2, true).apply {
            setBackgroundDrawable(ui.shape(Color.WHITE, 8))
            elevation = ui.dp(6).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        fun populate() {
            rows.removeAllViews()
            val addresses = library.recent()
            if (addresses.isEmpty()) rows.addView(ui.label("최근 사용한 주소가 없습니다.").apply { ui.inset(this, 12, 12) })
            for (address in addresses) {
                rows.addView(ui.row().apply {
                    addView(ui.action(address) {
                        host.setText(address)
                        host.setSelection(host.length())
                        popup.dismiss()
                    }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.action("×") {
                        library.removeAddress(address)
                        populate()
                    }.apply { contentDescription = "$address 최근 주소 삭제" })
                })
            }
            val height = minOf(ui.dp(48) * addresses.size.coerceAtLeast(1), ui.dp(288), popup.getMaxAvailableHeight(hostRow))
            if (popup.isShowing) popup.update(hostRow, hostRow.width, height) else popup.height = height
        }
        populate()
        popup.showAsDropDown(hostRow)
    }

    val open get() = panel.visibility == View.VISIBLE
    fun show(settings: ConnectionSettings, draft: Bundle? = null) {
        draftHosts.clear()
        for (option in ConnectionPath.entries) draft?.getString(option.name)?.let { draftHosts[option] = it }
        route = ConnectionPath.entries.firstOrNull { it.name == draft?.getString("route") } ?: settings.path
        choices.setOnCheckedChangeListener(null)
        choices.check(routes.getValue(route).id)
        displayHost(draftHosts[route] ?: settings.host)
        choices.setOnCheckedChangeListener(::routeSelected)
        port.setText(draft?.getString("port") ?: settings.port.toString())
        pairingPort.setText(draft?.getString("pair") ?: settings.pairingPort.toString())
        for (field in listOf(host,port,pairingPort)) field.error = null
        panel.visibility = View.VISIBLE
    }
    fun snapshot() = Bundle().apply {
        draftHosts.forEach { (path,value) -> putString(path.name,value) }
        putString(route.name,host.text.toString())
        putString("route",route.name)
        putString("port",port.text.toString())
        putString("pair",pairingPort.text.toString())
    }
    fun changed(settings: ConnectionSettings) = route != settings.path || route.host(host.text.toString()) != settings.host ||
        port.text.toString().toIntOrNull() != settings.port || pairingPort.text.toString().toIntOrNull() != settings.pairingPort
    fun read(): ConnectionSettings? {
        val address = route.host(host.text.toString())
        if (route != ConnectionPath.Direct && (address.isBlank() || address.any { it.isWhitespace() })) {
            host.error = "PC에 표시된 주소를 입력하세요."; host.requestFocus(); return null
        }
        fun number(field: EditText): Int? {
            val value = field.text.toString().toIntOrNull()
            if (value != null && value in 1..65535) return value
            field.error = "1부터 65535 사이의 포트를 입력하세요."; field.requestFocus(); return null
        }
        val session = number(port) ?: return null
        val pairing = number(pairingPort) ?: return null
        if (session == pairing) {
            pairingPort.error = "등록 포트는 세션 포트와 다른 번호를 입력하세요."
            pairingPort.requestFocus()
            return null
        }
        return ConnectionSettings(route,if (route == ConnectionPath.Direct) "" else address,session,pairing)
    }
}
