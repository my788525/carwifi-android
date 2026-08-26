package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Server 酱（sctapi.ftqq.com）。 */
class ServerChanChannel(override val config: PresetInterface) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val title = TemplateRenderer.render(config.titleTemplate.ifBlank { ChannelConfig.DEFAULT_TITLE }, msg)
            val desp = TemplateRenderer.render(config.template, msg)
            val url = "https://sctapi.ftqq.com/SCT${config.token}.send"
            val code = Http.postForm(url, mapOf("title" to title, "desp" to desp))
            if (code !in 200..299) throw IOException("Server 酱返回 HTTP $code")
        }
    }
}
