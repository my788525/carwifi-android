package com.carwifi.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.carwifi.app.core.CoreService
import com.carwifi.app.data.AppSettings
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.shizuku.ShizukuStarter
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.ComponentGate
import com.carwifi.app.util.DeviceUtils
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

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        uiLogger = AuditLogger(filesDir)
        ShizukuStarter.init()

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
        MonitorScheduler.schedule(this)
        audit = uiLogger.recent()

        // 首启即按当前设置同步监听组件（短信转发关闭则真正停止监听），无需点开开关
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ComponentGate.sync(this@MainActivity, settingsStore.current()) }
        }

        // 开关变更后同步监听组件启用状态
        val onPatch: (AppSettings.() -> AppSettings) -> Unit = { transform ->
            lifecycleScope.launch(Dispatchers.IO) {
                settingsStore.update(transform)
                runCatching { ComponentGate.sync(this@MainActivity, settingsStore.current()) }
            }
        }

        // 持续同步最新设置（包含 Shizuku 授权结果）
        lifecycleScope.launch {
            settingsStore.settings.collect { settings = it }
        }

        setContent {
            CarWifiTheme {
                SettingsScreen(
                    settings = settings,
                    isXiaomi = DeviceUtils.isXiaomi(),
                    onPatch = onPatch,
                    shizukuReady = settings.shizukuReady,
                    onRequestShizuku = { requestShizuku() },
                    onOpenNotifListener = { openNotifListener() },
                    onOpenBatteryOpt = { openBatteryOptimization() },
                    onStartService = {
                        CoreService.start(this@MainActivity)
                        audit = uiLogger.recent()
                    },
                    onReplayQueued = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            com.carwifi.app.dispatch.Forwarder.replayQueued(this@MainActivity)
                        }
                    },
                    auditLines = audit,
                    onRefreshAudit = { audit = uiLogger.recent() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audit = uiLogger.recent()
    }

    private fun requestShizuku() {
        ShizukuStarter.requestPermission(this, 1001) { granted ->
            lifecycleScope.launch {
                settingsStore.update { copy(shizukuReady = granted) }
            }
            Toast.makeText(
                this,
                if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝",
                Toast.LENGTH_SHORT
            ).show()
        }
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
}
