package com.carwifi.app.util

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 轻量 HTTP 工具（基于 HttpURLConnection，零额外依赖）。 */
object Http {

    fun postJson(url: String, json: String, timeoutMs: Int = 15000): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        return conn.responseCode.also { conn.disconnect() }
    }

    fun postForm(url: String, params: Map<String, String>, timeoutMs: Int = 15000): Int {
        val body = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn.responseCode.also { conn.disconnect() }
    }

    fun postRaw(url: String, body: String, contentType: String, timeoutMs: Int = 15000): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn.responseCode.also { conn.disconnect() }
    }

    fun get(url: String, timeoutMs: Int = 15000): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return conn.responseCode.also { conn.disconnect() }
    }
}
