package com.carwifi.app.workers

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carwifi.app.data.AlertQueue
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import com.carwifi.app.tethering.TetheringController
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.BatteryUtils
import com.carwifi.app.util.DeviceUtils
import com.carwifi.app.util.NightModeManager
import kotlinx.coroutines.flow.first

/**
 * 低功耗周期监测（默认 15 分钟一次，WorkManager 最短间隔）：
 * 1. 电量阈值边沿触发低电量告警（仅跌破阈值时发一次，回升后重置）。
 * 2. 检测夜间模式由「激活 → 解除」的跳变，解除即补发缓存消息。
 * 3. 热点保活：充电中且开启开关，但热点被系统/厂商自动关闭时，自动恢复。
 *
 * 不轮询、不常驻唤醒，满足「监听不需要太频繁」的省电要求。
 */
class MonitorWorker(ctx: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val s = store.settings.first()
        val logger = AuditLogger(applicationContext.filesDir)
        val tethering = TetheringController(applicationContext)

        // ---- 电量阈值（边沿触发）----
        val battery = BatteryUtils.level(applicationContext)
        if (s.lowBatteryEnabled && battery in 0..100) {
            if (battery <= s.batteryThreshold) {
                if (!s.lowBatteryAlerted) {
                    Forwarder.forward(
                        applicationContext,
                        AlertMessage(AlertType.LOW_BATTERY, battery = battery)
                    )
                    store.update { copy(lowBatteryAlerted = true) }
                }
            } else if (s.lowBatteryAlerted) {
                store.update { copy(lowBatteryAlerted = false) }
            }
        }

        // ---- 热点保活（应对厂商「无连接自动关闭热点」等场景）----
        val controlHotspot = !DeviceUtils.isXiaomi() || s.shizukuReady
        if (s.tetheringEnabled && controlHotspot && BatteryUtils.isCharging(applicationContext)) {
            if (!tethering.isHotspotOn()) {
                val ok = tethering.startHotspot()
                if (ok) logger.log("热点保活：检测到未开启，已自动恢复")
            }
        }

        // ---- 夜间模式解除 → 补发 ----
        val active = NightModeManager.isActive(s)
        val hadQueued = AlertQueue.parse(s.queuedMessagesJson).isNotEmpty()
        if (!active && (s.nightModePrevActive || hadQueued)) {
            Forwarder.replayQueued(applicationContext)
        }
        if (s.nightModePrevActive != active) {
            store.update { copy(nightModePrevActive = active) }
        }

        // 失败队列重试（非夜间时段才发送，夜间继续缓存）
        if (!active) Forwarder.retryFailed(applicationContext)

        logger.log("周期监测：电量=$battery% 夜间模式=${if (active) "激活" else "未激活"}")
        return androidx.work.ListenableWorker.Result.success()
    }
}
