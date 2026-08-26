package com.carwifi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub 拉取「App 特性介绍」（仓库根 app-features-pp.json）。
 *
 * 该文件独立于 APK 存放，可热更新：修改 GitHub 上的 JSON 即可更新 App 内
 * 「App 特性」弹窗内容，无需重新发版。拉取结果缓存到 filesDir，离线时也能
 * 用上一次的结果。
 */
object AppInfo {
    const val RAW_URL =
        "https://raw.githubusercontent.com/my788525/carwifi-android/master/app-features.json"
    private const val CACHE = "app_features.json"

    /** 拉取最新特性列表（失败则退回缓存）。 */
    suspend fun fetch(context: Context): List<AppFeature> {
        val fresh = runCatching { download() }.getOrNull()
        if (fresh != null) {
            runCatching { context.filesDir.resolve(CACHE).writeText(fresh) }
            return parse(fresh)
        }
        return getCached(context)
    }

    /** 读取上一次缓存的特性列表。 */
    fun getCached(context: Context): List<AppFeature> {
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

    private fun parse(text: String): List<AppFeature> {
        val out = mutableListOf<AppFeature>()
        runCatching {
            val root = JSONObject(text)
            val arr = root.optJSONArray("features") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += AppFeature(
                    icon = o.optString("icon", "✨"),
                    title = o.optString("title", ""),
                    desc = o.optString("desc", "")
                )
            }
        }
        return out
    }
}

/** 单条 App 特性。 */
data class AppFeature(
    val icon: String = "✨",
    val title: String = "",
    val desc: String = ""
)
