package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.AuditLogger
import kotlinx.coroutines.delay

/**
 * 统一消息调度器：把 AlertMessage 并发发往所有已启用渠道的所有接口配置，
 * 带 3 次指数退避重试，并记录审计日志。
 *
 * 渠道（ChannelConfig）是「容器」，实际发送目标是挂载其下的各个接口（PresetInterface）。
 * 接口未指定模板时，回退使用渠道的默认模板。
 */
class MessageDispatcher(private val logger: AuditLogger) {

    /** @return 至少有一个接口发送成功返回 true；全部失败返回 false（供失败队列重试）。 */
    suspend fun dispatch(msg: AlertMessage, channels: List<ChannelConfig>): Boolean {
        val targets = mutableListOf<Pair<ChannelConfig, PresetInterface>>()
        channels.filter { it.enabled }.forEach { ch ->
            ch.interfaces.forEach { iface -> targets += ch to iface }
        }
        if (targets.isEmpty()) {
            logger.log("无可用接口配置，跳过 ${msg.type.label}")
            return true
        }
        var anySuccess = false
        targets.forEach { (ch, rawIface) ->
            val iface = if (rawIface.template.isBlank()) {
                rawIface.copy(
                    template = ch.defaultTemplate,
                    titleTemplate = if (rawIface.titleTemplate.isBlank()) ch.defaultTitleTemplate else rawIface.titleTemplate
                )
            } else {
                rawIface
            }
            val built = ChannelFactory.build(iface) ?: return@forEach
            var last: Result<Unit> = Result.failure(Exception("未执行"))
            repeat(RETRY_TIMES) { attempt ->
                last = built.send(msg)
                if (last.isSuccess) {
                    anySuccess = true
                    return@forEach
                }
                delay((attempt + 1) * RETRY_BACKOFF_MS)
            }
            val status = if (last.isSuccess) "成功" else "失败(${last.exceptionOrNull()?.message})"
            val label = iface.name.ifBlank { iface.type.label }
            logger.log("接口[$label](${ch.name}) $status · ${msg.type.label} · ${msg.sender}")
        }
        return anySuccess
    }

    companion object {
        private const val RETRY_TIMES = 3
        private const val RETRY_BACKOFF_MS = 2000L
    }
}
