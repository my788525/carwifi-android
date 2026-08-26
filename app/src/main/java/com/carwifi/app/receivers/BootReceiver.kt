package com.carwifi.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carwifi.app.core.CoreService
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.util.ComponentGate
import com.carwifi.app.workers.MonitorScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 开机 / 替换安装后自启核心服务与周期监测，并按上次设置同步监听组件。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                CoreService.start(ctx)
                MonitorScheduler.schedule(ctx)
                // 开机即按上次设置同步监听组件，无需点开应用
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { ComponentGate.sync(ctx, SettingsStore(ctx).current()) }
                }
            }
        }
    }
}
