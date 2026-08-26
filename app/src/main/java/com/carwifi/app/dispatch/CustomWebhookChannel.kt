package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 自定义 Webhook：支持 GET / POST，Body 可由用户用占位符自定义（可写 JSON）。 */
class CustomWebhookChannel(override val config: PresetInterface) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val rendered = TemplateRenderer.render(config.template, msg)
            val code = when (config.method.uppercase()) {
                "GET" -> Http.get(config.server)
                else -> {
                    if (rendered.trim().startsWith("{")) {
                        Http.postJson(config.server, rendered)
                    } else {
                        Http.postRaw(config.server, rendered, "text/plain; charset=utf-8")
                    }
                }
            }
            if (code !in 200..299) throw IOException("Webhook 返回 HTTP $code")
        }
    }
}
