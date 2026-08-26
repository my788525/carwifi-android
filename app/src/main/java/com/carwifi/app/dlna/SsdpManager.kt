package com.carwifi.app.dlna

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets

/**
 * SSDP 发现层（UPnP 必备）。
 *
 * - 监听多播组 239.255.255.250:1900，对 M-SEARCH 单播回响应（让车机媒体 App 立即发现）。
 * - 周期性向多播组发送 NOTIFY alive（保活，通常 30s 一次，max-age 1800s）。
 * - 退出时发送 byebye 并释放 Wifi MulticastLock。
 *
 * 不依赖任何外部库，使用原生 java.net 多播套接字。
 * 需要权限 CHANGE_WIFI_MULTICAST_STATE（已在 Manifest 声明）。
 */
class SsdpManager(
    private val context: Context,
    private val uuid: String,
    private val ip: String,
    private val port: Int
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    private val group: InetAddress = InetAddress.getByName("239.255.255.250")
    private val ssdpPort = 1900
    private val aliveIntervalMs = 30_000L
    private var lastAlive = 0L

    private val usnUuid = "uuid:$uuid"
    private val usnRoot = "uuid:$uuid::upnp:rootdevice"
    private val usnDevice = "uuid:$uuid::${DlnaContent.DEVICE_TYPE}"

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "carwifi-ssdp").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { socket?.leaveGroup(group) }
        runCatching { socket?.close() }
        runCatching { lock?.release() }
        thread = null
    }

    private fun runLoop() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("CarWifiDlna").apply {
                setReferenceCounted(false)
                acquire()
            }

            val sock = MulticastSocket(ssdpPort).apply {
                reuseAddress = true
                soTimeout = 1000
                joinGroup(group)
            }
            socket = sock
            lastAlive = 0L
            sendMulticast(buildNotify("upnp:rootdevice", usnRoot))
            sendMulticast(buildNotify(DlnaContent.DEVICE_TYPE, usnDevice))
            sendMulticast(buildNotify(usnUuid, usnUuid))

            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val data = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    if (data.startsWith("M-SEARCH", ignoreCase = true) && data.contains("ST:")) {
                        handleSearch(data, pkt)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 超时用于驱动周期 alive，忽略
                } catch (e: Exception) {
                    if (!running) break
                }
                maybeSendAlive()
            }
            sendMulticast(buildByeBye("upnp:rootdevice", usnRoot))
            sendMulticast(buildByeBye(DlnaContent.DEVICE_TYPE, usnDevice))
            sendMulticast(buildByeBye(usnUuid, usnUuid))
        } finally {
            runCatching { socket?.leaveGroup(group) }
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
    }

    private fun handleSearch(data: String, pkt: DatagramPacket) {
        val st = when {
            data.contains("upnp:rootdevice", ignoreCase = true) -> "upnp:rootdevice" to usnRoot
            data.contains(DlnaContent.DEVICE_TYPE, ignoreCase = true) -> DlnaContent.DEVICE_TYPE to usnDevice
            data.contains("uuid:$uuid", ignoreCase = true) -> usnUuid to usnUuid
            data.contains("ssdp:all", ignoreCase = true) -> "ssdp:all" to usnRoot
            else -> null
        } ?: return
        sendUnicast(buildResponse(st.first, st.second), pkt.address, pkt.port)
    }

    private fun maybeSendAlive() {
        val now = System.currentTimeMillis()
        if (now - lastAlive >= aliveIntervalMs) {
            lastAlive = now
            sendMulticast(buildNotify("upnp:rootdevice", usnRoot))
            sendMulticast(buildNotify(DlnaContent.DEVICE_TYPE, usnDevice))
            sendMulticast(buildNotify(usnUuid, usnUuid))
        }
    }

    private fun location(): String = "http://$ip:$port/desc.xml"

    private fun buildNotify(nt: String, usn: String): ByteArray {
        val s = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: ${location()}\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:alive\r\n" +
            "SERVER: ${DlnaContent.serverString()}\r\n" +
            "USN: $usn\r\n\r\n"
        return s.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildResponse(st: String, usn: String): ByteArray {
        val s = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: ${location()}\r\n" +
            "SERVER: ${DlnaContent.serverString()}\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n\r\n"
        return s.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildByeBye(nt: String, usn: String): ByteArray {
        val s = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "USN: $usn\r\n\r\n"
        return s.toByteArray(StandardCharsets.UTF_8)
    }

    private fun sendMulticast(bytes: ByteArray) {
        runCatching {
            val pkt = DatagramPacket(bytes, bytes.size, group, ssdpPort)
            socket?.send(pkt)
        }
    }

    private fun sendUnicast(bytes: ByteArray, addr: InetAddress, port: Int) {
        runCatching {
            val pkt = DatagramPacket(bytes, bytes.size, addr, if (port > 0) port else ssdpPort)
            socket?.send(pkt)
        }
    }
}
