package com.carwifi.app.fileshare

import android.content.Context
import com.carwifi.app.data.AppSettings
import com.carwifi.app.dlna.DeviceUuid
import com.carwifi.app.dlna.SsdpManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * HTTP 文件共享 + DLNA 媒体服务器 生命周期管理（单例）。
 *
 * 与热点强绑定：仅当「热点已开启 + 文件共享开关打开」时才运行服务器，热点关闭即停止。
 * 当 DLNA 开关打开时，额外启动 SSDP 发现层（让车机原生媒体 App 自动发现「CarWifi Media」）。
 * 所有调用幂等，可安全在开机、充电、断电、热点状态变更、设置变更时反复 reconcile。
 */
object FileShareManager {

    @Volatile private var server: FileShareServer? = null
    @Volatile private var ssdp: SsdpManager? = null
    @Volatile private var currentPort: Int = 0
    @Volatile private var dlnaRunning: Boolean = false

    /** 共享根目录：App 私有外部存储下的 share/（无需 MANAGE_EXTERNAL_STORAGE）。 */
    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "share").also { it.mkdirs() }

    fun isRunning(): Boolean = server != null
    fun isDlnaRunning(): Boolean = dlnaRunning

    fun start(context: Context, port: Int, password: String, dlnaEnabled: Boolean) {
        if (server != null) return
        val root = rootDir(context)
        val uuid = DeviceUuid.get(context)
        val ip = getLocalIp() ?: "127.0.0.1"
        val s = FileShareServer(root, port, password, dlnaEnabled, uuid, ip)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        currentPort = port
        if (dlnaEnabled) {
            ssdp = SsdpManager(context, uuid, ip, port).also { it.start() }
            dlnaRunning = true
        }
    }

    fun stop() {
        runCatching { server?.stop() }
        runCatching { ssdp?.stop() }
        server = null
        ssdp = null
        dlnaRunning = false
        currentPort = 0
    }

    /** 依据当前设置与热点状态，确保服务器处于应有状态（开热点+开关开→运行，否则停止）。 */
    fun applyState(
        context: Context,
        enabled: Boolean,
        port: Int,
        password: String,
        hotspotOn: Boolean,
        dlnaEnabled: Boolean
    ) {
        if (hotspotOn && enabled) {
            if (server == null) start(context, port, password, dlnaEnabled)
            else if (dlnaEnabled != dlnaRunning) {
                // DLNA 开关与运行态不一致时重启以套用
                stop()
                start(context, port, password, dlnaEnabled)
            }
        } else {
            if (server != null) stop()
        }
    }

    fun applyFromSettings(context: Context, s: AppSettings, hotspotOn: Boolean) {
        applyState(context, s.fileShareEnabled, s.fileSharePort, s.fileSharePassword, hotspotOn, s.dlnaEnabled)
    }

    /** 返回车机可访问的地址；未运行或无法获取本机 IP 时返回空串。 */
    fun getAccessUrl(context: Context): String {
        if (server == null) return ""
        val ip = getLocalIp() ?: return ""
        return "http://$ip:$currentPort"
    }

    /** 遍历网络接口，取首个私网 IPv4（车机与本机同处热点子网）。 */
    fun getLocalIp(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { it.hostAddress }
            }?.firstOrNull { isPrivate(it) }
        }.getOrNull()
    }

    private fun isPrivate(ip: String): Boolean = when {
        ip.startsWith("192.168.") -> true
        ip.startsWith("10.") -> true
        ip.startsWith("172.") -> {
            val seg = ip.substringAfter("172.").substringBefore('.').toIntOrNull()
            seg != null && seg in 16..31
        }
        else -> false
    }
}
