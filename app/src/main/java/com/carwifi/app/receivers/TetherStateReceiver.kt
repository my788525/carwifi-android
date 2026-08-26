package com.carwifi.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.tethering.TetheringController
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.BatteryUtils
import com.carwifi.app.util.HotspotPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 监听热点开关状态变更广播（android.net.conn.TETHER_STATE_CHANGED）。
 * 当满足「充电中 + 开关开启 + 本应用应接管」但检测到热点实际未开启时，
 * 立即经 Shizuku 恢复热点——比 15 分钟周期轮询更及时，且为事件驱动、更省电。
 *
 * 仅在 CoreService（前台常驻）生命周期内动态注册，随服务销毁而注销。
 */
class TetherStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != "android.net.conn.TETHER_STATE_CHANGED") return
        scope.launch {
            val s = SettingsStore(ctx).settings.first()
            if (!s.tetheringEnabled) return@launch
            if (!HotspotPolicy.shouldControlByApp(s)) return@launch
            if (!BatteryUtils.isCharging(ctx)) return@launch

            val t = TetheringController(ctx)
            if (t.isHotspotOn()) return@launch

            val now = System.currentTimeMillis()
            if (now - lastAttempt < DEBOUNCE_MS) return@launch
            lastAttempt = now

            val ok = t.startHotspot()
            AuditLogger(ctx.filesDir).log(
                if (ok) "热点状态变更：检测到未开启，已自动恢复"
                else "热点状态变更：恢复失败（Shizuku 未就绪，可检查授权）"
            )
        }
    }

    fun dispose() = scope.cancel()

    companion object {
        private const val DEBOUNCE_MS = 8000L
        @Volatile var lastAttempt: Long = 0
    }
}
