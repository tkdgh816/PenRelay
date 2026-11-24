package dev.penrelay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.security.MessageDigest

internal class DirectCodeGroup(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var current: DirectEndpoint? = null

    val connected get() = current != null

    fun endpoint(): DirectEndpoint {
        if (callback == null) throw IOException("PC 연결 QR을 스캔하거나 PC 연결 코드를 입력하세요.")
        return current ?: throw IOException("코드로 연결한 PC의 무선 주소를 기다리고 있습니다.")
    }

    fun join(code: String, ready: () -> Unit, failed: (String) -> Unit) {
        close()
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.US_ASCII))
        val ssid = "DIRECT-PR-" + digest.take(4).joinToString("") { "%02X".format(it) }
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(code).build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val listener = object : ConnectivityManager.NetworkCallback() {
            var started = false
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (callback !== this) return
                val local = properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
                val owner = properties.dhcpServerAddress ?: properties.routes.firstOrNull {
                    it.isDefaultRoute && it.gateway is Inet4Address
                }?.gateway as? Inet4Address
                if (local == null || owner == null) return
                current = DirectEndpoint(owner, local, network)
                if (!started) { started = true; ready() }
            }
            override fun onLost(network: Network) {
                if (callback === this && current?.network == network) current = null
            }
            override fun onUnavailable() {
                if (callback !== this) return
                callback = null
                current = null
                failed("무선 그룹에 연결하지 못했습니다. PC 대기 화면의 현재 코드와 태블릿의 시스템 승인을 확인하세요.")
            }
        }
        callback = listener
        try { connectivity.requestNetwork(request, listener, Handler(Looper.getMainLooper()), 60_000) }
        catch (_: SecurityException) {
            callback = null
            failed("Android 설정에서 펜 릴레이의 주변 기기 권한과 위치 서비스 상태를 확인하세요.")
        }
    }

    fun close() {
        val previous = callback
        callback = null
        current = null
        if (previous != null) connectivity.unregisterNetworkCallback(previous)
    }
}
