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
import com.carwifi.app.fileshare.FileShareManager
import com.carwifi.app.receivers.TetherStateReceiver
import com.carwifi.app.tethering.TetheringController
import com.carwifi.app.ui.MainActivity
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.BatteryUtils
import com.carwifi.app.util.HotspotPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：仅负责「充电自动开热点」与状态展示。
 * 短信 / 未接来电 / 低电量 的转发已改为事件驱动（接收器 / 监听器 / WorkManager 周期任务），
 * 不再在此轮询，大幅降低耗电。
 * 文件共享服务器随热点生命周期在此协调启停（热点开+开关开 → 运行，否则停止）。
 * onTaskRemoved 自拉起，配合开机自启实现常驻。
 */
class CoreService : android.app.Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var settingsStore: SettingsStore
    private lateinit var auditLogger: AuditLogger
    private lateinit var tethering: TetheringController
    private lateinit var tetherReceiver: TetherStateReceiver
    private lateinit var reconcileReceiver: BroadcastReceiver

    override fun onCreate() {
        super.onCreate()
        try {
            settingsStore = SettingsStore(applicationContext)
            auditLogger = AuditLogger(filesDir)
            tethering = TetheringController(applicationContext)
            createChannel()
            startForeground(NOTIF_ID, buildNotification())
            auditLogger.log("核心服务已启动（常驻，仅负责热点与状态）")

            // 动态注册热点状态变更监听：掉线即恢复，比 15 分钟轮询更及时、更省电
            tetherReceiver = TetherStateReceiver()
            registerReceiver(tetherReceiver, IntentFilter("android.net.conn.TETHER_STATE_CHANGED"))

            // 注册「设置变更协调」本地广播：来自 MainActivity 开关变更，用于即时起停文件共享
            reconcileReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) = reconcileFileShare()
            }
            registerReceiver(reconcileReceiver, IntentFilter(ACTION_RECONCILE))

            // 开机 / 重启后若已在充电（如常驻车充），立即按设置开热点。
            // 设备已在充电时系统不会重发 ACTION_POWER_CONNECTED，故需主动检测。
            if (BatteryUtils.isCharging(applicationContext)) {
                scope.launch {
                    val s = settingsStore.settings.first()
                    if (s.tetheringEnabled && HotspotPolicy.shouldControlByApp(s)) {
                        val ok = tethering.startHotspot()
                        auditLogger.log(
                            if (ok) "已在充电：开机自动开启热点"
                            else "已在充电：应用无法自动开热点，请在系统设置手动开启"
                        )
                    }
                    delay(3000)
                    reconcileFileShare()
                }
            }
        } catch (t: Throwable) {
            // 服务与 Activity 同进程：任何未捕获异常都会拖垮整个进程（表现为点图标无反应）。
            // 此处兜底，确保即使服务启动失败，主界面仍可用。
            runCatching { auditLogger.log("核心服务启动异常：${t.message}") }
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
        runCatching { unregisterReceiver(tetherReceiver) }
        runCatching { tetherReceiver.dispose() }
        runCatching { unregisterReceiver(reconcileReceiver) }
        runCatching { FileShareManager.stop() }
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 事件处理 ----

    private fun onPowerConnected() {
        scope.launch {
            val s = settingsStore.settings.first()
            if (s.tetheringEnabled && HotspotPolicy.shouldControlByApp(s)) {
                val ok = tethering.startHotspot()
                auditLogger.log(
                    if (ok) "充电：已开启热点"
                    else "充电：应用无法自动开热点，请在系统设置手动开启"
                )
            } else if (s.tetheringEnabled) {
                auditLogger.log("充电：本机由系统自动任务管理热点，应用跳过接管")
            }
            // 热点实际开启需要时间，稍候再协调文件共享起停
            delay(3000)
            reconcileFileShare()
        }
    }

    private fun onPowerDisconnected() {
        scope.launch {
            val s = settingsStore.settings.first()
            if (s.tetheringEnabled && HotspotPolicy.shouldControlByApp(s)) {
                val ok = tethering.stopHotspot()
                auditLogger.log(if (ok) "断电：已关闭热点" else "断电：热点关闭失败")
            }
            // 断电后若热点被关闭，文件共享应随之停止
            reconcileFileShare()
        }
    }

    /**
     * 依据当前热点实际状态与文件共享开关，确保 HTTP 文件服务器处于应有状态。
     * 幂等、可反复调用；热点开+开关开 → 运行，否则停止。
     */
    private fun reconcileFileShare() {
        scope.launch {
            val s = settingsStore.settings.first()
            val hotspotOn = tethering.isHotspotOn()
            FileShareManager.applyFromSettings(applicationContext, s, hotspotOn)
            auditLogger.log(
                "文件共享协调：热点=${if (hotspotOn) "开" else "关"} 开关=${s.fileShareEnabled} " +
                    "状态=${if (FileShareManager.isRunning()) "运行中" else "已停"}"
            )
        }
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
        /** 本地广播：设置变更后通知服务重新协调文件共享起停。 */
        const val ACTION_RECONCILE = "com.carwifi.app.ACTION_RECONCILE"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "carwifi_core"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, CoreService::class.java)) }
        }
    }
}
