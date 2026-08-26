package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * PushPlus（pushplus.plus）：用户 token 鉴权，通过微信 / APP / 浏览器插件等渠道接收。
 * - token = 用户 token（必填）
 * - extra = 群组编码 topic（可选，留空仅发送给自己；填写后可一对多推送给群组成员）
 * 内容以 HTML 模板发送，正文中的换行会转为 <br> 以保证可读。
 */
class PushPlusChannel(override val config: PresetInterface) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val server = if (config.server.isBlank()) "https://www.pushplus.plus/send" else config.server.trimEnd('/')
            val title = TemplateRenderer.render(config.titleTemplate.ifBlank { ChannelConfig.DEFAULT_TITLE }, msg)
            val content = TemplateRenderer.render(config.template, msg).replace("\n", "<br>")
            val json = JSONObject().apply {
                put("token", config.token)
                put("title", title)
                put("content", content)
                put("template", "html")
                if (config.extra.isNotBlank()) put("topic", config.extra.trim())
            }.toString()
            val resp = Http.postJsonBody(server, json)
            val code = runCatching { JSONObject(resp).optInt("code", -1) }.getOrDefault(-1)
            if (code != 200) throw IOException("PushPlus 返回 code=$code（token 有误）")
        }
    }
}
