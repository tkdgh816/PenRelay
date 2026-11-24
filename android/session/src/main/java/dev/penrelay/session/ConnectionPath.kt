package dev.penrelay.session

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

enum class ConnectionPath(val label: String) {
    Wifi("Wi-Fi (같은 공유기)"),
    Direct("Wi-Fi Direct (PC 직접 연결)"),
    Reverse("USB ADB (USB 디버깅)"),
    Tether("USB 테더링 (USB 네트워크)");

    fun host(address: String) = if (this == Reverse) "127.0.0.1" else address.trim()

    // 케이블이 빠진 동안 같은 주소로 Wi-Fi 연결을 시도하지 않는다.
    internal fun bind(socket: Socket, peer: InetSocketAddress) {
        if (this == Direct) throw SocketException("Wi-Fi Direct 인터페이스가 필요합니다.")
        if (this != Tether) return
        val remote = peer.address as? Inet4Address ?: throw SocketException("PC의 USB IPv4 주소가 필요합니다.")
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val local = interfaces?.asSequence()
            ?.filter { it.name.matches(Regex("(usb|rndis|ncm)[0-9]+")) && it.isUp }
            ?.flatMap { it.interfaceAddresses.asSequence() }
            ?.firstOrNull { iface ->
                val local = iface.address as? Inet4Address ?: return@firstOrNull false
                val prefix = iface.networkPrefixLength.toInt()
                prefix in 1..32 && local != remote && (0 until prefix).all { bit ->
                    ((local.address[bit / 8].toInt() xor remote.address[bit / 8].toInt()) and (128 shr (bit % 8))) == 0
                }
            } ?: throw SocketException("USB 테더링과 PC의 USB 어댑터 주소를 확인해 주세요.")
        socket.bind(InetSocketAddress(local.address, 0))
    }
}
