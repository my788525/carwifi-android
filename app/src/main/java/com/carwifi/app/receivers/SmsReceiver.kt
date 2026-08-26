package com.carwifi.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import com.carwifi.app.util.AuditLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 接收新短信广播，提取发件人与正文后交 Forwarder 统一处理（含夜间缓存）。 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 诊断：无论后续是否真正转发，先记录「已收到短信广播」，便于排查「测试成功但不转发」。
        // 若审计日志里看不到这条，说明系统根本没把广播投递给本应用（权限/ROM 限制）。
        val logger = AuditLogger(ctx.filesDir)
        logger.log("📩 收到短信广播（SMS_RECEIVED）")
        // 双保险：转发开关关闭时不处理（组件亦已禁用，此处防范 ROM 差异）。
        val settings = runBlocking { SettingsStore(ctx).settings.first() }
        if (!settings.smsForwardEnabled) {
            logger.log("「短信转发」总开关关闭，已忽略本次短信（请在设置中打开顶部「短信转发」开关）")
            return
        }
        if (settings.channels.isEmpty()) {
            logger.log("无渠道配置，已忽略本次短信（请先添加并启用 Bark 等渠道）")
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val sb = StringBuilder()
        var sender = ""
        for (sms in messages) {
            if (sender.isBlank()) sender = sms.displayOriginatingAddress ?: ""
            sb.append(sms.displayMessageBody ?: "")
        }
        Forwarder.forwardAsync(
            ctx,
            AlertMessage(AlertType.SMS, sender, sb.toString())
        )
    }
}
