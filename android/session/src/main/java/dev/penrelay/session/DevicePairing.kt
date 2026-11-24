package dev.penrelay.session

import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class DevicePairing(private val identity: Array<KeyManager>,
    private val bind: (SSLSocket, InetSocketAddress) -> Unit = { _, _ -> },
    private val onSocket: (SSLSocket) -> Unit = {}) {
    // 지문은 PC 화면에서 전달받은 SHA-256(DER SubjectPublicKeyInfo)이다.
    fun register(endpoint: InetSocketAddress, serverPin: String, secret: ByteArray): Boolean {
        require(secret.size == 32)
        return connect(endpoint, serverPin).use { socket ->
            socket.soTimeout = 125_000 // PC 승인 창 2분과 결과 전송 시간
            socket.outputStream.write(secret)
            when (socket.inputStream.read()) {
                1 -> true
                0 -> false
                else -> throw IOException("등록 결과를 받지 못했습니다.")
            }
        }
    }

    fun openSession(endpoint: InetSocketAddress, serverPin: String, modes: List<String>, sensorCaps: List<String>,
                    registration: InetSocketAddress? = null): ClientSession {
        try { return ClientSession.negotiate(connect(endpoint, serverPin), modes, sensorCaps) }
        catch (error: IOException) {
            // TLS 1.3 인증 거부가 EOF/reset으로 전달되는 구현에서도 네트워크 장애와 폐기를 구분한다.
            if (registration != null && error !is java.net.ConnectException) {
                val status = try {
                    connect(registration, serverPin, "penrelay-status/1").use {
                        if (it.applicationProtocol == "penrelay-status/1") it.inputStream.read() else -1
                    }
                } catch (_: IOException) { -1 }
                if (status == 0) throw PairingRequiredException()
            }
            throw error
        }
    }

    internal fun connect(endpoint: InetSocketAddress, serverPin: String, protocol: String? = null): SSLSocket {
        require(serverPin.matches(Regex("[0-9A-F]{64}")))
        val expected = serverPin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("서버 인증 전용")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("서버 인증서 없음")
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("PC 공개키 지문 불일치")
            }
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(identity, arrayOf(trust), null) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            onSocket(socket)
            bind(socket, endpoint)
            socket.enabledProtocols = arrayOf("TLSv1.3")
            if (protocol != null) socket.sslParameters = socket.sslParameters.apply { applicationProtocols = arrayOf(protocol) }
            socket.tcpNoDelay = true
            socket.soTimeout = 5000
            socket.connect(endpoint, 5000)
            socket.startHandshake()
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
}
