package com.carwifi.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内自更新：检查 GitHub Release 最新版本、下载 APK、调起系统安装器。
 * 便于分发朋友后用户发版他们自动收到，最小维护量。
 */
object UpdateChecker {

    private const val REPO = "my788525/carwifi-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class ReleaseInfo(val tag: String, val apkUrl: String)

    /** 拉取最新 Release 的 tag 与 APK 下载地址。 */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(txt)
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets") ?: JSONArray()
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val ct = a.optString("content_type")
                val name = a.optString("name")
                if (ct.contains("android") || name.endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            if (tag.isBlank() || url.isBlank()) null else ReleaseInfo(tag, url)
        }.getOrNull()
    }

    /** 语义化版本比较：latest 是否比 current 新。 */
    fun isNewer(current: String, latest: String): Boolean {
        val c = parseVersion(current)
        val l = parseVersion(latest)
        val n = maxOf(c.size, l.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (b != a) return b > a
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-').map {
            it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0
        }

    /** 下载 APK 到应用外部下载目录，返回文件或 null。 */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit = {}): java.io.File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                val dest = java.io.File(dir, "carwifi-update.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 60000
                }
                val total = conn.contentLength.toLong()
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                conn.disconnect()
                dest
            }.getOrNull()
        }

    /** 调起系统安装器安装已下载的 APK（经 FileProvider 暴露，避免 FileUriExposed）。 */
    fun installApk(context: Context, file: java.io.File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
