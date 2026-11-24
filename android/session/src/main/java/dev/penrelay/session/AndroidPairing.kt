package dev.penrelay.session

import android.content.Context
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.X509KeyManager

// 생성과 네트워크 I/O는 작업 스레드에서 호출한다.
class AndroidPairing(context: Context, cancellation: CancellationSignal? = null,
    private val path: ConnectionPath = ConnectionPath.Wifi,
    bind: (Socket, InetSocketAddress) -> Unit = path::bind) {
    private val app = context.applicationContext
    private val preferences = context.getSharedPreferences("penrelay-pairing", Context.MODE_PRIVATE)
    private val entry = deviceKey()
    private val manager = object : X509KeyManager {
        override fun getClientAliases(type: String, issuers: Array<Principal>?) =
            if (type == "EC") arrayOf(ALIAS) else null
        override fun chooseClientAlias(types: Array<String>, issuers: Array<Principal>?, socket: Socket?) =
            ALIAS.takeIf { "EC" in types }
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS)
            entry.certificateChain.map { it as X509Certificate }.toTypedArray() else null
        override fun getPrivateKey(alias: String?): PrivateKey? = entry.privateKey.takeIf { alias == ALIAS }
        override fun getServerAliases(type: String, issuers: Array<Principal>?): Array<String>? = null
        override fun chooseServerAlias(type: String, issuers: Array<Principal>?, socket: Socket?): String? = null
    }
    private val client = DevicePairing(arrayOf(manager), bind = bind) { socket ->
        cancellation?.setOnCancelListener { socket.close() }
    }

    val deviceFingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(entry.certificate.publicKey.encoded).joinToString("") { "%02X".format(it) }

    fun register(endpoint: InetSocketAddress, serverPin: String, secret: ByteArray): Boolean {
        checkEndpoint(endpoint)
        if (!client.register(endpoint, serverPin, secret)) return false
        if (!preferences.edit().putString("server-pin", serverPin).putInt("registration-port", endpoint.port).commit())
            throw IOException("PC 키 지문 저장 실패")
        return true
    }

    fun openSession(endpoint: InetSocketAddress, modes: List<String>, sensorCaps: List<String>): ClientSession {
        checkEndpoint(endpoint)
        val pin = preferences.getString("server-pin", null) ?: throw PairingRequiredException()
        val port = preferences.getInt("registration-port", 0)
        val registration = if (port != 0) InetSocketAddress(endpoint.hostString, port) else null
        return client.openSession(endpoint, pin, modes, sensorCaps, registration)
    }

    private fun checkEndpoint(endpoint: InetSocketAddress) {
        if (path == ConnectionPath.Reverse) require(endpoint.address?.isLoopbackAddress == true)
        else if (!LanPermission.allowed(app)) throw SecurityException("로컬 네트워크 권한 필요")
    }

    companion object {
        fun registeredHost(context: Context): String? =
            context.getSharedPreferences("penrelay-pairing", Context.MODE_PRIVATE).getString("server-pin", null)

        private const val ALIAS = "penrelay-device-tls-v2"

        @Synchronized private fun deviceKey(): KeyStore.PrivateKeyEntry {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt는 TLS CertificateVerify의 다이제스트를 계산한 뒤 Keystore에 서명을 요청한다.
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .build()
                KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            }
            return store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        }
    }
}
