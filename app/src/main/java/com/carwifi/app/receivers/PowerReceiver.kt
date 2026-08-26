package com.carwifi.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carwifi.app.core.CoreService

/** 充电 / 断电 → 通知核心服务控制热点。 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> CoreService.ACTION_POWER_CONNECTED
            Intent.ACTION_POWER_DISCONNECTED -> CoreService.ACTION_POWER_DISCONNECTED
            else -> null
        }
        action?.let {
            ctx.startForegroundService(Intent(ctx, CoreService::class.java).setAction(it))
        }
    }
}
