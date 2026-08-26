package com.carwifi.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.carwifi.app.core.CoreService
import com.carwifi.app.data.AppFeature
import com.carwifi.app.data.AppInfo
import com.carwifi.app.data.AppSettings
import com.carwifi.app.data.ChannelCatalog
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.fileshare.FileShareManager
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.ComponentGate
import com.carwifi.app.util.UpdateChecker
import com.carwifi.app.workers.MonitorScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var uiLogger: AuditLogger

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* 权限结果不阻塞，缺失时功能降级 */ }

    private var settings by mutableStateOf(AppSettings())
    private var audit by mutableStateOf<List<String>>(emptyList())
    private var batteryExempt by mutableStateOf(false)
    private var updateStatus by mutableStateOf("")
    private var updateTarget by mutableStateOf<UpdateChecker.ReleaseInfo?>(null)
    private var releaseBody by mutableStateOf("")
    private var appFeatures by mutableStateOf<List<AppFeature>>(emptyList())

    private val githubReleasesUrl = "https://github.com/my788525/carwifi-android/releases"
    private var fileShareUrl by mutableStateOf("")
    private var fileSharePath by mutableStateOf("")

    // GitHub 接口配置目录（供推送渠道勾选）
    private var catalog by mutableStateOf<List<PresetInterface>>(emptyList())

    // SAF 授权文件夹选择器（共享系统目录如 Music/Download）
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val list = settingsStore.current().extraShareUris().toMutableList()
                list.add(uri.toString())
                settingsStore.update { copy(extraShareUrisJson = org.json.JSONArray(list).toString()) }
                // 重启文件服务器以加载新根目录
                FileShareManager.stop()
                sendBroadcast(Intent(CoreService.ACTION_RECONCILE))
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        uiLogger = AuditLogger(filesDir)

        // 运行时权限（侧载场景同样需要授予）。未接来电走通知监听，无需 READ_CALL_LOG。
        val needed = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(needed.toTypedArray())

        // 拉起常驻服务
        CoreService.start(this)
        // 启动低功耗周期监测（电量阈值 + 夜间补发 + 热点保活）
        runCatching { MonitorScheduler.schedule(this) }
        audit = uiLogger.recent()
        refreshBatteryExempt()

        // 拉取 GitHub 接口配置目录（失败则用上次缓存）
        lifecycleScope.launch(Dispatchers.IO) {
            catalog = ChannelCatalog.fetch(this@MainActivity)
        }

        // 拉取 GitHub 上的 App 特性介绍（热更新，失败用缓存）
        lifecycleScope.launch(Dispatchers.IO) {
            appFeatures = AppInfo.fetch(this@MainActivity)
        }

        // 首启即按当前设置同步监听组件（短信转发关闭则真正停止监听），无需点开开关
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ComponentGate.sync(this@MainActivity, settingsStore.current()) }
        }

        // 电池优化豁免：仅首次自动申请一次（常驻保活关键）。小米等厂商仍建议手动在系统设置放行。
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = getSystemService(PowerManager::class.java)
            val exempt = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
            if (!exempt && !settingsStore.current().batteryExemptPrompted) {
                settingsStore.update { copy(batteryExemptPrompted = true) }
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        }

        // 开关变更后同步监听组件启用状态，并通知服务重新协调文件共享起停
        val onPatch: (AppSettings.() -> AppSettings) -> Unit = { transform ->
            lifecycleScope.launch(Dispatchers.IO) {
                settingsStore.update(transform)
                runCatching { ComponentGate.sync(this@MainActivity, settingsStore.current()) }
                runCatching { sendBroadcast(Intent(CoreService.ACTION_RECONCILE)) }
            }
        }

        // 持续同步最新设置（包含 Shizuku 授权结果）
        lifecycleScope.launch {
            settingsStore.settings.collect { settings = it }
        }

        // 首启自动检查更新（24h 节流），仅静默检查不弹窗
        lifecycleScope.launch(Dispatchers.IO) {
            val last = settingsStore.current().lastUpdateCheck
            if (System.currentTimeMillis() - last > 24 * 3600 * 1000L) checkUpdate()
        }

        setContent {
            CarWifiTheme {
                SettingsScreen(
                    settings = settings,
                    isXiaomi = com.carwifi.app.util.DeviceUtils.isXiaomi(),
                    onPatch = onPatch,
                    onOpenNotifListener = { openNotifListener() },
                    onOpenBatteryOpt = { openBatteryOptimization() },
                    batteryExempt = batteryExempt,
                    onStartService = {
                        CoreService.start(this@MainActivity)
                        audit = uiLogger.recent()
                    },
                    onReplayQueued = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            Forwarder.replayQueued(this@MainActivity)
                        }
                    },
                    versionName = currentVersion(),
                    updateStatus = updateStatus,
                    releaseBody = releaseBody,
                    appFeatures = appFeatures,
                    githubUrl = githubReleasesUrl,
                    onCheckUpdate = { lifecycleScope.launch(Dispatchers.IO) { checkUpdate(showIfLatest = true) } },
                    auditLines = audit,
                    onRefreshAudit = { audit = uiLogger.recent() },
                    fileShareUrl = fileShareUrl,
                    fileSharePath = fileSharePath,
                    catalog = catalog,
                    onRefreshCatalog = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            catalog = ChannelCatalog.fetch(this@MainActivity)
                        }
                    },
                    extraShareUris = settings.extraShareUris(),
                    onPickFolder = { folderLauncher.launch(null) },
                    onRemoveFolder = { uri ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val list = settingsStore.current().extraShareUris().toMutableList()
                            list.remove(uri)
                            settingsStore.update { copy(extraShareUrisJson = org.json.JSONArray(list).toString()) }
                            FileShareManager.stop()
                            sendBroadcast(Intent(CoreService.ACTION_RECONCILE))
                        }
                    },
                    onTestChannel = { ch ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val ok = Forwarder.testChannel(this@MainActivity, ch)
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (ok) "✅ 测试发送成功（详见审计日志）" else "❌ 测试失败，详见审计日志",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )

                updateTarget?.let { info ->
                    AlertDialog(
                        onDismissRequest = { updateTarget = null },
                        confirmButton = {
                            TextButton(onClick = {
                                updateTarget = null
                                downloadAndInstall(info.apkUrl)
                            }) { Text("下载并安装") }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateTarget = null }) { Text("暂不") }
                        },
                        title = { Text("发现新版本 ${info.tag}") },
                        text = { Text("当前版本 ${currentVersion()}。下载后将跳转系统安装器完成更新（同签名可覆盖安装）。") }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audit = uiLogger.recent()
        refreshBatteryExempt()
        fileShareUrl = FileShareManager.getAccessUrl(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val n = settingsStore.current().extraShareUris().size
            fileSharePath = if (n > 0) "app + $n 个授权目录" else "app"
        }
    }

    private fun currentVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("1.0.0")

    private fun refreshBatteryExempt() {
        batteryExempt = runCatching {
            (getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
    }

    private fun openNotifListener() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun openBatteryOptimization() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    /** 检查 GitHub Release 最新版本；发现新版本弹窗，否则更新状态文案。 */
    private suspend fun checkUpdate(showIfLatest: Boolean = false) {
        val latest = UpdateChecker.fetchLatest()
        val cur = currentVersion()
        settingsStore.update { copy(lastUpdateCheck = System.currentTimeMillis()) }
        if (latest == null) {
            updateStatus = "检查更新失败（网络/接口异常）"
            return
        }
        // 无论是否有新版本，都展示最新 Release 的说明正文（最近更新内容）
        releaseBody = latest.body
        if (UpdateChecker.isNewer(cur, latest.tag)) {
            updateStatus = "发现新版本 ${latest.tag}"
            updateTarget = latest
        } else {
            updateStatus = "已是最新（${cur}）"
            if (showIfLatest) {
                runOnUiThread { Toast.makeText(this, "已是最新版本 ${cur}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun downloadAndInstall(url: String) {
        runOnUiThread { Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show() }
        lifecycleScope.launch(Dispatchers.IO) {
            val file = UpdateChecker.downloadApk(this@MainActivity, url) { /* 进度可扩展 */ }
            if (file != null) {
                UpdateChecker.installApk(this@MainActivity, file)
            } else {
                runOnUiThread { Toast.makeText(this@MainActivity, "下载失败，请手动前往 Release 下载", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
