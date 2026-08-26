package com.carwifi.app.fileshare

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.Base64

/**
 * 极简 HTTP 文件服务器（基于 NanoHTTPD），用于让连上本机热点的车机
 * 通过浏览器访问 / 下载 / 上传手机上的共享文件。
 *
 * 设计原则（贴合车载「静默、稳定、最小维护」）：
 * - 仅监听高位端口（默认 8080），无需 Root。
 * - 共享目录固定为 App 私有外部存储下的 share/，无需 MANAGE_EXTERNAL_STORAGE 等重权限。
 * - 可选 HTTP Basic Auth（车机 LAN 内建议设密码）。
 * - 防目录穿越：所有路径限制在 root 之内。
 */
class FileShareServer(
    private val root: File,
    port: Int,
    private val password: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        // 可选密码保护
        if (password.isNotEmpty()) {
            val auth = session.headers["authorization"]
            val expected = "Basic " + Base64.getEncoder()
                .encodeToString("carwifi:$password".toByteArray())
            if (auth != expected) {
                val r = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "Unauthorized"
                )
                r.addHeader("WWW-Authenticate", "Basic realm=\"CarWifi\"")
                return r
            }
        }

        val target = safeResolve(session.uri)
        return when (session.method) {
            Method.GET -> handleGet(target)
            Method.PUT -> handlePut(session, target)
            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )
        }
    }

    private fun handleGet(target: File): Response {
        if (!target.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
        if (target.isDirectory) {
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderListing(target))
        }
        val mime = guessMime(target.name)
        return newFixedLengthResponse(Response.Status.OK, mime, target.inputStream(), target.length())
    }

    private fun handlePut(session: IHTTPSession, target: File): Response {
        if (target.isDirectory) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Cannot PUT a directory"
            )
        }
        return try {
            target.parentFile?.mkdirs()
            val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
            val buf = if (len > 0) {
                session.inputStream.readNBytes(len.toInt())
            } else {
                session.inputStream.readBytes()
            }
            target.outputStream().use { it.write(buf) }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Saved ${target.name}")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error: ${e.message}"
            )
        }
    }

    /** 将 URI 解析为 root 内的文件，越界则返回 root（后续按目录 404 处理）。 */
    private fun safeResolve(uri: String): File {
        val rel = uri.split("?")[0].trimStart('/')
        val f = File(root, rel)
        val rootCanon = root.canonicalPath
        if (!f.canonicalPath.startsWith(rootCanon)) return root
        return f
    }

    private fun renderListing(dir: File): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<title>CarWifi 共享文件</title>")
        sb.append("<style>body{font-family:sans-serif;padding:16px}h1{font-size:18px}")
        sb.append("li{margin:4px 0}a{text-decoration:none;color:#1769ff}</style></head><body>")
        sb.append("<h1>本机共享文件</h1>")
        sb.append("<p>把车机要访问的文件放进此目录即可。下方可上传文件到当前目录。</p>")
        sb.append("<ul>")
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            val name = f.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val icon = if (f.isDirectory) "📁" else "📄"
            sb.append("<li>$icon <a href=\"${f.name}\">$name</a>")
            if (!f.isDirectory) sb.append(" <small>(${f.length()} B)</small>")
            sb.append("</li>")
        } ?: sb.append("<li>（空目录）</li>")
        sb.append("</ul>")
        sb.append("<hr><h2>上传文件到当前目录</h2>")
        sb.append("<input type=\"file\" id=\"file\"><button onclick=\"upload()\">上传</button>")
        sb.append("<script>function upload(){var f=document.getElementById('file').files[0];")
        sb.append("if(!f)return;var x=new XMLHttpRequest();x.open('PUT',encodeURIComponent(f.name));")
        sb.append("x.send(f);x.onload=function(){location.reload();};}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> MIME_OCTET_STREAM
    }

    companion object {
        private const val MIME_PLAINTEXT = "text/plain; charset=utf-8"
        private const val MIME_OCTET_STREAM = "application/octet-stream"
    }
}
