package dev.penrelay.app

import android.app.Activity
import android.app.AlertDialog
import android.net.Network
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

internal data class DirectEndpoint(val owner: Inet4Address, val local: Inet4Address, val network: Network) {
    fun bind(socket: Socket, peer: InetSocketAddress) {
        if (peer.address != owner) throw IOException("Wi-Fi Direct 그룹의 PC 주소가 바뀌었습니다.")
        network.bindSocket(socket)
        socket.bind(InetSocketAddress(local, 0))
    }
}

internal class WifiDirectLink(private val activity: Activity) {
    private val group = DirectCodeGroup(activity)
    private val codeFormat = Regex("[A-F0-9]{20}")
    val connected get() = group.connected
    fun endpoint() = group.endpoint()

    fun scanResult(contents: String, ready: () -> Unit, report: (String) -> Unit) {
        val prefix = "PENRELAY-DIRECT-1:"
        val code = contents.removePrefix(prefix)
        if (!contents.startsWith(prefix) || !codeFormat.matches(code)) {
            report("PC 대시보드의 ‘Wi-Fi Direct 연결 QR’을 스캔하세요. 기기 등록 QR은 ‘PC 초대 QR 스캔’에서 사용하세요.")
            return
        }
        group.join(code, ready, report)
    }

    fun askCode(ready: () -> Unit, report: (String) -> Unit) {
        val field = EditText(activity).apply {
            setSingleLine()
            isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "PC에 표시된 20자리 코드"
        }
        val showCode = CheckBox(activity).apply {
            text = "연결 코드 표시"
            setOnCheckedChangeListener { _, visible ->
                val cursor = field.selectionEnd.coerceAtLeast(0)
                field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
                field.setSelection(cursor.coerceAtMost(field.length()))
            }
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
            addView(field)
            addView(showCode)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("PC 연결 코드 입력")
            .setView(form).setNegativeButton("취소", null).setPositiveButton("연결", null).show()
        dialog.setOnDismissListener { field.text.clear() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = field.text.toString().trim().uppercase(Locale.ROOT)
            if (codeFormat.matches(code)) {
                dialog.dismiss()
                group.join(code, ready, report)
            } else field.error = "PC 연결 코드 20자리를 입력하세요."
        }
    }

    fun release() = group.close()
}
