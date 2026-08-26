package com.carwifi.app.dispatch

import android.content.Context
import com.carwifi.app.data.AlertQueue
import com.carwifi.app.data.AppSettings
import com.carwifi.app.data.SettingsStore
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.util.AuditLogger
import com.carwifi.app.util.NightModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 统一转发中枢：取代各接收器里直接调用 MessageDispatcher 的做法。
 * - 夜间模式激活时：把消息缓存进队列，不立即发送。
 * - 否则：立即渲染模板并发往所有已启用渠道。
 * - replayQueued()：在夜间模式解除后被调用，把缓存消息补发。
 *
 * 采用事件驱动 + 短协程，不持有任何长轮询，耗电极低。
 */
object Forwarder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 异步入口，供 BroadcastReceiver / NotificationListener 直接调用。 */
    fun forwardAsync(context: Context, msg: AlertMessage) {
        scope.launch { forward(context, msg) }
    }

    /** 同步入口，供 WorkManager / UI 调用。 */
    suspend fun forward(context: Context, msg: AlertMessage) {
        val store = SettingsStore(context)
        val s = store.settings.first()
        if (s.channels.isEmpty()) {
            AuditLogger(context.filesDir).log("无可用渠道，跳过 ${msg.type.label}")
            return
        }

        // 隐私：隐藏短信正文
        val effective =
            if (msg.type == AlertType.SMS && s.hideBody) msg.copy(body = "（内容已隐藏）") else msg

        if (NightModeManager.isActive(s)) {
            val list = (AlertQueue.parse(s.queuedMessagesJson) + effective).takeLast(200)
            store.update { copy(queuedMessagesJson = AlertQueue.toJson(list)) }
            AuditLogger(context.filesDir)
                .log("夜间模式：已缓存 ${msg.type.label}（待补发 ${list.size} 条）")
            return
        }

        val ok = MessageDispatcher(AuditLogger(context.filesDir)).dispatch(effective, s.channels)
        if (!ok) {
            val failed = (AlertQueue.parse(s.failedMessagesJson) + effective).takeLast(200)
            store.update { copy(failedMessagesJson = AlertQueue.toJson(failed)) }
            AuditLogger(context.filesDir)
                .log("转发全部失败，已进失败队列（待重试 ${failed.size} 条）")
        }
    }

    /** 解除夜间模式后补发全部缓存消息，并顺带冲刷失败队列。 */
    suspend fun replayQueued(context: Context) {
        val store = SettingsStore(context)
        val s = store.settings.first()
        val list = AlertQueue.parse(s.queuedMessagesJson)
        if (list.isNotEmpty()) {
            val logger = AuditLogger(context.filesDir)
            val dispatcher = MessageDispatcher(logger)
            logger.log("夜间模式解除：补发 ${list.size} 条缓存消息")
            list.forEach { dispatcher.dispatch(it, s.channels) }
            store.update { copy(queuedMessagesJson = "[]") }
        }
        retryFailed(context)
    }

    /**
     * 测试某个渠道：向该渠道挂载的全部接口发送一条测试消息，返回是否至少有一个成功。
     * 结果会写入审计日志，便于用户在无界面反馈时排查。
     */
    suspend fun testChannel(context: Context, channel: ChannelConfig): Boolean {
        val logger = AuditLogger(context.filesDir)
        // 提示前置条件：测试只验证单通道发送能力，但真实短信还要求总开关开启 + RECEIVE_SMS 权限。
        val pre = SettingsStore(context).settings.first()
        if (!pre.smsForwardEnabled) {
            logger.log("⚠️ 测试渠道[${channel.name}]：但「短信转发」总开关已关闭，真实短信不会转发（仅本次测试能发）")
        }
        if (pre.channels.isEmpty()) {
            logger.log("⚠️ 当前无已保存渠道配置，测试所用配置可能未持久化")
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val msg = AlertMessage(
            type = AlertType.SMS,
            sender = "CarWifi 测试",
            body = "这是一条来自 CarWifi 的测试推送 ✅（时间 $time）",
            timestamp = System.currentTimeMillis()
        )
        logger.log("手动测试渠道[${channel.name}]（${channel.interfaces.size} 个接口）")
        val ok = MessageDispatcher(logger).dispatch(msg, listOf(channel))
        logger.log(
            if (ok) "✅ 测试渠道[${channel.name}] 发送成功"
            else "❌ 测试渠道[${channel.name}] 发送失败（检查接口配置 / 网络，详见审计日志）"
        )
        return ok
    }

    /**
     * 重试失败队列：对每条消息再次尝试发送，成功即移出队列。
     * 周期任务（非夜间时段）与夜间解除补发时都会调用，确保网络抖动不丢消息。
     */
    suspend fun retryFailed(context: Context) {
        val store = SettingsStore(context)
        val s = store.settings.first()
        val list = AlertQueue.parse(s.failedMessagesJson)
        if (list.isEmpty()) return
        val logger = AuditLogger(context.filesDir)
        val dispatcher = MessageDispatcher(logger)
        val stillFailed = mutableListOf<AlertMessage>()
        list.forEach {
            if (dispatcher.dispatch(it, s.channels)) {
                logger.log("失败队列补发成功：${it.type.label} · ${it.sender}")
            } else {
                stillFailed += it
            }
        }
        if (stillFailed.size != list.size) {
            store.update { copy(failedMessagesJson = AlertQueue.toJson(stillFailed)) }
            logger.log("失败队列重试：成功 ${list.size - stillFailed.size} 条，仍失败 ${stillFailed.size} 条")
        }
    }
}
