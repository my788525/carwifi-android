package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.PresetInterface
import com.carwifi.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * WxPusher（wxpusher.zjiecode.com）：appToken 鉴权，通过 UID 单发或 Topic 群发。
 * - token  = appToken（必填）
 * - extra  = 接收目标（必填）：
 *      - 直接填 `UID_xxx`（或逗号分隔多个）单发；
 *      - 直接填数字 Topic ID（或逗号分隔多个）群发；
 *      - 也可精确写法 `uids=UID_a,UID_b;topicIds=123,456`。
 * 内容以 HTML（contentType=2）发送，正文中的换行会转为 <br> 以保证可读。
 */
class WxPusherChannel(override val config: PresetInterface) : Channel {
    override suspend fun send(msg: AlertMessage): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val server = if (config.server.isBlank()) "https://wxpusher.zjiecode.com/api/send/message" else config.server.trimEnd('/')
            val title = TemplateRenderer.render(config.titleTemplate.ifBlank { ChannelConfig.DEFAULT_TITLE }, msg)
            val content = TemplateRenderer.render(config.template, msg).replace("\n", "<br>")
            val (uids, topicIds) = parseTargets(config.extra)
            if (uids.isEmpty() && topicIds.isEmpty()) {
                throw IOException("WxPusher 缺少接收目标（extra 需填 UID 或 Topic ID）")
            }
            val json = JSONObject().apply {
                put("appToken", config.token)
                put("content", content)
                put("summary", title.take(100))
                put("contentType", 2)
                if (uids.isNotEmpty()) put("uids", JSONArray(uids))
                if (topicIds.isNotEmpty()) put("topicIds", JSONArray(topicIds))
            }.toString()
            val resp = Http.postJsonBody(server, json)
            val code = runCatching { JSONObject(resp).optInt("code", -1) }.getOrDefault(-1)
            if (code != 1000) throw IOException("WxPusher 返回 code=$code（appToken 或接收目标有误）")
        }
    }

    /** 解析 extra 为 (uids, topicIds)。 */
    private fun parseTargets(extra: String): Pair<List<String>, List<Int>> {
        val uids = mutableListOf<String>()
        val topicIds = mutableListOf<Int>()
        val e = extra.trim()
        if (e.isEmpty()) return uids to topicIds
        val uPart = Regex("""uids\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(e)?.groupValues?.get(1)
        val tPart = Regex("""topicIds\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(e)?.groupValues?.get(1)
        uPart?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let { uids.addAll(it) }
        tPart?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let { topicIds.addAll(it) }
        if (uPart != null || tPart != null) return uids to topicIds
        if (e.contains("UID_", ignoreCase = true)) {
            e.split(",").map { it.trim() }.filter { it.isNotBlank() }.let { uids.addAll(it) }
        } else {
            e.split(",").mapNotNull { it.trim().toIntOrNull() }.let { topicIds.addAll(it) }
        }
        return uids to topicIds
    }
}
