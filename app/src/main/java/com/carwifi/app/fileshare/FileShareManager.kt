package com.carwifi.app.fileshare

import android.content.Context
import com.carwifi.app.data.AppSettings
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * HTTP 文件共享生命周期管理（单例）。
 *
 * 与热点强绑定：仅当「热点已开启 + 开关打开」时才运行服务器，热点关闭即停止。
 * 所有调用幂等，可安全在开机、充电、断电、热点状态变更、设置变更时反复 reconcile。
 */
object FileShareManager {

    @Volatile private var server: FileShareServer? = null
    @Volatile private var currentPort: Int = 0

    /** 共享根目录：App 私有外部存储下的 share/（无需 MANAGE_EXTERNAL_STORAGE）。 */
    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "share").also { it.mkdirs() }

    fun isRunning(): Boolean = server != null

    fun start(context: Context, port: Int, password: String) {
        if (server != null) return
        val root = rootDir(context)
        val s = FileShareServer(root, port, password)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        currentPort = port
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        currentPort = 0
    }

    /** 依据当前设置与热点状态，确保服务器处于应有状态（开热点+开关开→运行，否则停止）。 */
    fun applyState(context: Context, enabled: Boolean, port: Int, password: String, hotspotOn: Boolean) {
        if (hotspotOn && enabled) {
            if (server == null) start(context, port, password)
        } else {
            if (server != null) stop()
        }
    }

    fun applyFromSettings(context: Context, s: AppSettings, hotspotOn: Boolean) {
        applyState(context, s.fileShareEnabled, s.fileSharePort, s.fileSharePassword, hotspotOn)
    }

    /** 返回车机可访问的地址；未运行或无法获取本机 IP 时返回空串。 */
    fun getAccessUrl(context: Context): String {
        if (server == null) return ""
        val ip = getLocalIp() ?: return ""
        return "http://$ip:$currentPort"
    }

    /** 遍历网络接口，取首个私网 IPv4（车机与本机同处热点子网）。 */
    private fun getLocalIp(): String? {
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
