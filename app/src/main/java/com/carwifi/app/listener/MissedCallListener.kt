package com.carwifi.app.listener

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.regex.Pattern

/**
 * 通过通知监听捕获「未接来电」系统通知（免 READ_CALL_LOG 敏感权限）。
 * 需用户在系统「通知使用权」中开启本服务。
 *
 * 转发开关关闭时，组件保持启用（保留系统授权）但在此处直接丢弃，做到「关闭不转发」，
 * 同时避免反复重新授权带来的维护负担。
 */
class MissedCallListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val NUMBER_RE = Pattern.compile("(\\+?\\d[\\d\\-\\s]{6,}\\d)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        // 只关注电话相关通知
        if (sbn.notification.category != "call" &&
            sbn.packageName != "com.android.server.telecom"
        ) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val combined = "$title $text"

        val isMissed = combined.contains("未接") || combined.contains("Missed")
        if (!isMissed) return

        val number = extractNumber(combined) ?: title.ifBlank { "未知号码" }
        val body = combined.trim()

        // 在 IO 协程内先判定转发开关，关闭则不转发（组件保留以维护系统授权）
        scope.launch {
            val enabled = SettingsStore(applicationContext).settings.first().missedCallForwardEnabled
            if (!enabled) return@launch
            Forwarder.forward(
                applicationContext,
                AlertMessage(AlertType.MISSED_CALL, number, body)
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extractNumber(s: String): String? {
        val m = NUMBER_RE.matcher(s)
        return if (m.find()) m.group(1)?.replace(" ", "") else null
    }
}
