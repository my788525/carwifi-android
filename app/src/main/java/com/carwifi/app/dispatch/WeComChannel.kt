package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** 企业微信机器人 webhook。 */
class WeComChannel(override val config: ChannelConfig) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val content = TemplateRenderer.render(config.template, msg)
            val url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=${config.token}"
            val json = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().put("content", content))
            }.toString()
            val code = Http.postJson(url, json)
            if (code !in 200..299) throw IOException("企业微信返回 HTTP $code")
        }
    }
}
