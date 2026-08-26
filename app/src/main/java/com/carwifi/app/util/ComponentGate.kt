package com.carwifi.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.carwifi.app.data.AppSettings
import com.carwifi.app.receivers.SmsReceiver

/**
 * 按设置同步「接收器 / 监听器」的启用状态，保证「打开才监听、关闭即静默」：
 *
 * - 短信转发关闭 → 禁用 SmsReceiver 组件。系统不再向本应用投递短信广播，真正停止监听，
 *   且不消耗任何唤醒。重新打开时恢复组件即可，无需重新授权。
 * - 未接来电转发关闭 → 由 MissedCallListener 在运行时判定开关后丢弃，组件保持启用。
 *   原因：NotificationListenerService 的「通知使用权」授权与组件启用状态绑定，若禁用组件，
 *   部分 ROM 会要求用户重新进入系统设置授权，增加维护量。保持组件启用、仅拦截转发，
 *   既满足「关闭不转发」，又避免反复重新授权的麻烦。
 *
 * 在首次启动、开关切换、开机自启时各调用一次，使得「无需点开应用」即按上次设置工作。
 */
object ComponentGate {

    fun sync(context: Context, settings: AppSettings) {
        setComponentEnabled(context, SmsReceiver::class.java, settings.smsForwardEnabled)
        // 未接来电组件保持启用（运行时拦截），此处不做切换。
    }

    private fun setComponentEnabled(context: Context, cls: Class<*>, enabled: Boolean) {
        val cn = ComponentName(context, cls)
        val pm = context.packageManager
        val desired = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            if (pm.getComponentEnabledSetting(cn) != desired) {
                pm.setComponentEnabledSetting(cn, desired, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
