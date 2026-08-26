package com.carwifi.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.dispatch.Forwarder
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 接收新短信广播，提取发件人与正文后交 Forwarder 统一处理（含夜间缓存）。 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 双保险：转发开关关闭时不处理（组件亦已禁用，此处防范 ROM 差异）。
        val enabled = runBlocking { SettingsStore(ctx).settings.first().smsForwardEnabled }
        if (!enabled) return
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
