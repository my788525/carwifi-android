package com.carwifi.app.data

import android.content.Context
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.ChannelType
import com.carwifi.app.model.PresetInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub 拉取「接口配置目录」。
 *
 * 用户在 GitHub 仓库根的 channels-catalog.json 中预置好各 Bark / Webhook 等服务器的
 * key/token、服务器地址、body 模板。手机端拉取后，只需在设置里勾选即可，无需手打长地址。
 * 拉取结果会缓存到 filesDir，离线时也能用上一次的结果。
 */
object ChannelCatalog {
    const val RAW_URL =
        "https://raw.githubusercontent.com/my788525/carwifi-android/master/channels-catalog.json"
    private const val CACHE = "channel_catalog.json"

    /** 拉取最新目录（失败则退回缓存）。 */
    suspend fun fetch(context: Context): List<PresetInterface> {
        val fresh = runCatching { download() }.getOrNull()
        if (fresh != null) {
            runCatching { context.filesDir.resolve(CACHE).writeText(fresh) }
            return parse(fresh)
        }
        return getCached(context)
    }

    /** 读取上一次缓存的目录。 */
    fun getCached(context: Context): List<PresetInterface> {
        return runCatching {
            val f = context.filesDir.resolve(CACHE)
            if (f.exists()) parse(f.readText(Charsets.UTF_8)) else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun download(): String {
        val conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .also { conn.disconnect() }
    }

    private fun parse(text: String): List<PresetInterface> {
        val out = mutableListOf<PresetInterface>()
        runCatching {
            val root = JSONObject(text)
            val arr = root.optJSONArray("interfaces") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = ChannelType.fromKey(o.optString("type")) ?: continue
                out += PresetInterface(
                    id = o.optString("id", ""),
                    name = o.optString("name", ""),
                    type = type,
                    server = o.optString("server", ""),
                    token = o.optString("token", ""),
                    method = o.optString("method", "POST").uppercase(),
                    template = o.optString("template", ChannelConfig.DEFAULT_TEMPLATE),
                    titleTemplate = o.optString("titleTemplate", ChannelConfig.DEFAULT_TITLE),
                    extra = o.optString("extra", "")
                )
            }
        }
        return out
    }
}
