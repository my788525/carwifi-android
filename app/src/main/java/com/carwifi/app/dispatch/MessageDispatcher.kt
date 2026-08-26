package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.util.AuditLogger
import kotlinx.coroutines.delay

/**
 * 统一消息调度器：把 AlertMessage 并发发往所有已启用的渠道，
 * 带 3 次指数退避重试，并记录审计日志。
 */
class MessageDispatcher(private val logger: AuditLogger) {

    suspend fun dispatch(msg: AlertMessage, channels: List<ChannelConfig>) {
        val built = channels.filter { it.enabled }.mapNotNull { ChannelFactory.build(it) }
        if (built.isEmpty()) {
            logger.log("无可用渠道，跳过 ${msg.type.label}")
            return
        }
        built.forEach { ch ->
            var last: Result<Unit> = Result.failure(Exception("未执行"))
            repeat(RETRY_TIMES) { attempt ->
                last = ch.send(msg)
                if (last.isSuccess) return@forEach
                delay((attempt + 1) * RETRY_BACKOFF_MS)
            }
            val status = if (last.isSuccess) "成功" else "失败(${last.exceptionOrNull()?.message})"
            logger.log("渠道[${ch.config.name}] $status · ${msg.type.label} · ${msg.sender}")
        }
    }

    companion object {
        private const val RETRY_TIMES = 3
        private const val RETRY_BACKOFF_MS = 2000L
    }
}
