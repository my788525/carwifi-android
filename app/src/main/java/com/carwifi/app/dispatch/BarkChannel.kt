package com.carwifi.app.dispatch

import android.util.Log
import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Bark：自建或官方(api.day.app)。device_key 放在 body。 */
class BarkChannel(override val config: PresetInterface) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val base = if (config.server.isBlank()) "https://api.day.app" else config.server.trimEnd('/')
            val title = TemplateRenderer.render(config.titleTemplate.ifBlank { ChannelConfig.DEFAULT_TITLE }, msg)
            val body = TemplateRenderer.render(config.template, msg)
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                if (config.token.isNotBlank()) put("device_key", config.token)
            }.toString()
            val code = Http.postJson("$base/push", json)
            if (code !in 200..299) throw IOException("Bark 返回 HTTP $code")
        }
    }
}
