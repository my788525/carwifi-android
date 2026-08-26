package com.carwifi.app.fileshare

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
 * 共享根目录支持多个：App 私有外部存储下的 share/ + 经 SAF 授权的系统目录（如 Music/Download）。
 * 当 DLNA 开关打开时，额外启动 SSDP 发现层（让车机原生媒体 App 自动发现「CarWifi Media」）。
 * 所有调用幂等，可安全在开机、充电、断电、热点状态变更、设置变更时反复 reconcile。
 */
object FileShareManager {

    @Volatile private var server: FileShareServer? = null
    @Volatile private var ssdp: SsdpManager? = null
    @Volatile private var currentPort: Int = 0
    @Volatile private var dlnaRunning: Boolean = false
    @Volatile private var currentRootSig: String = ""

    /** App 私有外部存储下的 share/（无需 MANAGE_EXTERNAL_STORAGE）。 */
    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "share").also { it.mkdirs() }

    fun isRunning(): Boolean = server != null
    fun isDlnaRunning(): Boolean = dlnaRunning

    /** 构建共享根目录列表：app 私有 share + 各 SAF 授权目录。 */
    fun buildRoots(context: Context, extraUris: List<String>): List<ShareRoot> {
        val list = mutableListOf<ShareRoot>(FileShareRoot("app", rootDir(context)))
        extraUris.forEachIndexed { i, u ->
            runCatching {
                val uri = Uri.parse(u)
                val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "folder$i"
                list += DocShareRoot(name, uri, context)
            }
        }
        return list
    }

    private fun rootsSignature(roots: List<ShareRoot>): String = roots.joinToString("|") { "${it.name}:${it::class.simpleName}" }

    fun start(context: Context, port: Int, password: String, dlnaEnabled: Boolean, roots: List<ShareRoot>) {
        if (server != null) return
        val uuid = DeviceUuid.get(context)
        val ip = getLocalIp() ?: "127.0.0.1"
        val s = FileShareServer(roots, port, password, dlnaEnabled, uuid, ip)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        currentPort = port
        currentRootSig = rootsSignature(roots)
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
        currentRootSig = ""
    }

    /**
     * 依据当前设置与热点状态，确保服务器处于应有状态（开热点+开关开→运行，否则停止）。
     * 根目录集合发生变化（如新增/移除 SAF 授权目录）时会自动重启以套用。
     */
    fun applyState(
        context: Context,
        enabled: Boolean,
        port: Int,
        password: String,
        hotspotOn: Boolean,
        dlnaEnabled: Boolean,
        extraUris: List<String>
    ) {
        if (hotspotOn && enabled) {
            val roots = buildRoots(context, extraUris)
            val sig = rootsSignature(roots)
            if (server == null) {
                start(context, port, password, dlnaEnabled, roots)
            } else if (dlnaEnabled != dlnaRunning || sig != currentRootSig) {
                // DLNA 开关或根目录集合不一致时重启以套用
                stop()
                start(context, port, password, dlnaEnabled, roots)
            }
        } else {
            if (server != null) stop()
        }
    }

    fun applyFromSettings(context: Context, s: AppSettings, hotspotOn: Boolean) {
        applyState(
            context,
            s.fileShareEnabled,
            s.fileSharePort,
            s.fileSharePassword,
            hotspotOn,
            s.dlnaEnabled,
            s.extraShareUris()
        )
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
