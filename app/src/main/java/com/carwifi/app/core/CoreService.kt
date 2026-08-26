package com.carwifi.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.carwifi.app.data.AppSettings
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.tethering.TetheringController
import com.carwifi.app.ui.MainActivity
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.BatteryUtils
import com.carwifi.app.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：仅负责「充电自动开热点」与状态展示。
 * 短信 / 未接来电 / 低电量 的转发已改为事件驱动（接收器 / 监听器 / WorkManager 周期任务），
 * 不再在此轮询，大幅降低耗电。
 * onTaskRemoved 自拉起，配合开机自启实现常驻。
 */
class CoreService : android.app.Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var settingsStore: SettingsStore
    private lateinit var auditLogger: AuditLogger
    private lateinit var tethering: TetheringController

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        auditLogger = AuditLogger(filesDir)
        tethering = TetheringController(applicationContext)
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        auditLogger.log("核心服务已启动（常驻，仅负责热点与状态）")

        // 开机 / 重启后若已在充电（如常驻车充），立即按设置开热点。
        // 设备已在充电时系统不会重发 ACTION_POWER_CONNECTED，故需主动检测。
        if (BatteryUtils.isCharging(applicationContext)) {
            scope.launch {
                val s = settingsStore.settings.first()
                if (s.tetheringEnabled && useShizukuForHotspot(s)) {
                    val ok = tethering.startHotspot()
                    auditLogger.log(
                        if (ok) "已在充电：开机自动开启热点"
                        else "已在充电：热点开启失败（Shizuku 未就绪，可在设置查看操作提示）"
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_POWER_CONNECTED -> onPowerConnected()
            ACTION_POWER_DISCONNECTED -> onPowerDisconnected()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 被系统清理后尝试自拉起，维持常驻
        runCatching { startService(Intent(applicationContext, CoreService::class.java)) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 事件处理 ----

    private fun onPowerConnected() {
        scope.launch {
            val s = settingsStore.settings.first()
            if (!s.tetheringEnabled) return@launch
            if (!useShizukuForHotspot(s)) {
                auditLogger.log("充电：本机由系统自动任务管理热点，应用跳过接管")
                return@launch
            }
            val ok = tethering.startHotspot()
            auditLogger.log(
                if (ok) "充电：已开启热点"
                else "充电：热点开启失败（Shizuku 未就绪，可在设置中查看操作提示）"
            )
        }
    }

    private fun onPowerDisconnected() {
        scope.launch {
            val s = settingsStore.settings.first()
            if (!s.tetheringEnabled) return@launch
            if (!useShizukuForHotspot(s)) {
                auditLogger.log("断电：本机由系统自动任务管理热点，应用跳过接管")
                return@launch
            }
            val ok = tethering.stopHotspot()
            auditLogger.log(if (ok) "断电：已关闭热点" else "断电：热点关闭失败")
        }
    }

    /**
     * 是否由本应用经 Shizuku 控制热点。
     * 小米/Redmi/POCO 由系统「自动任务」接管热点，无需应用操作；未授权 Shizuku 时跳过，
     * 避免无效尝试与日志噪音。
     */
    private fun useShizukuForHotspot(s: AppSettings): Boolean {
        return !DeviceUtils.isXiaomi() || s.shizukuReady
    }

    // ---- 通知 ----

    private fun createChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            getString(com.carwifi.app.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.carwifi.app.R.string.foreground_notification_title))
            .setContentText(getString(com.carwifi.app.R.string.foreground_notification_text))
            .setSmallIcon(com.carwifi.app.R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_POWER_CONNECTED = "com.carwifi.app.ACTION_POWER_CONNECTED"
        const val ACTION_POWER_DISCONNECTED = "com.carwifi.app.ACTION_POWER_DISCONNECTED"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "carwifi_core"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, CoreService::class.java)) }
        }
    }
}
