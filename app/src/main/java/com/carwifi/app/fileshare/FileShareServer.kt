package com.carwifi.app.fileshare

import com.carwifi.app.dlna.DlnaContent
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 车载文件共享 + UPnP AV 媒体服务器（基于 NanoHTTPD，单端口同时承载）。
 *
 * 设计原则（贴合车载「静默、稳定、最小维护」）：
 * - 仅监听高位端口（默认 8080），无需 Root。
 * - 共享根目录支持多个：App 私有外部存储下的 share/ + 经 SAF 授权的系统目录（如 Music/Download）。
 *   各根以「根名」路由（/根名/...）。
 * - 可选 HTTP Basic Auth（车机 LAN 内建议设密码），但 DLNA 控制端点按标准不鉴权。
 * - 防目录穿越：所有路径限制在各根之内。
 * - 媒体文件支持 HTTP Range（206 + Content-Range），主流播放器可流式播放并拖动。
 * - DLNA 端点（desc.xml、cds.xml、cm.xml、ctl 与 media 路由）仅当 dlnaEnabled 时激活，
 *   配合 SsdpManager 即构成一台可被车机原生媒体 App 发现的媒体服务器。
 */
class FileShareServer(
    private val roots: List<ShareRoot>,
    private val port: Int,
    private val password: String,
    private val dlnaEnabled: Boolean,
    private val uuid: String,
    private val localIp: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.split("?")[0]

        // ---- DLNA 端点（按标准不鉴权）----
        if (dlnaEnabled) {
            when {
                uri == "/desc.xml" ->
                    return newFixedLengthResponse(Response.Status.OK, "application/xml", DlnaContent.deviceDescription(uuid, port))
                uri == "/cds.xml" ->
                    return newFixedLengthResponse(Response.Status.OK, "application/xml", DlnaContent.contentDirectoryScpd())
                uri == "/cm.xml" ->
                    return newFixedLengthResponse(Response.Status.OK, "application/xml", DlnaContent.connectionManagerScpd())
                uri == "/ctl/ContentDirectory" && session.method == Method.POST ->
                    return handleContentDirectory(session)
                uri == "/ctl/ConnectionManager" && session.method == Method.POST ->
                    return handleConnectionManager(session)
                uri == "/media" || uri.startsWith("/media/") ->
                    return handleMedia(session, uri)
            }
        }

        // ---- 普通文件服务（可选密码）----
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

        return when (session.method) {
            Method.GET -> handleGet(session, uri)
            Method.PUT -> handlePut(session, uri)
            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )
        }
    }

    // ---------- 文件浏览 / 下载（带 Range）----------

    private fun handleGet(session: IHTTPSession, uri: String): Response {
        val clean = uri.trimStart('/')
        if (clean.isEmpty()) return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, renderRootsListing())
        val seg = clean.split('/', limit = 2)
        val root = findRoot(seg[0]) ?: return notFound()
        val rel = if (seg.size > 1) seg[1] else ""
        if (!root.exists(rel)) return notFound()
        if (root.isDir(rel)) return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, renderListing(seg[0], rel, root.list(rel)))
        return serveStream(root, rel, session)
    }

    // 支持 HTTP Range 的通用文件响应（媒体流式播放 / 拖动的核心）。
    private fun serveStream(root: ShareRoot, rel: String, session: IHTTPSession): Response {
        val len = root.size(rel) ?: return notFound()
        val mime = mimeFor(rel.substringAfterLast('/', ""))
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.substring(6).split(",", limit = 2)[0].split("-", limit = 2)
            var start: Long
            var end: Long
            if (a.isEmpty()) {
                start = (len - b.toLong()).coerceAtLeast(0L)
                end = len - 1
            } else {
                start = a.toLongOrNull() ?: 0L
                end = if (b.isEmpty()) len - 1 else b.toLongOrNull()?.coerceAtMost(len - 1) ?: (len - 1)
            }
            if (start > end || start >= len) {
                val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                r.addHeader("Content-Range", "bytes */$len")
                return r
            }
            val size = end - start + 1
            val stream = (root.open(rel) ?: return notFound()).also { it.skip(start) }
            val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, size)
            resp.addHeader("Content-Range", "bytes $start-$end/$len")
            resp.addHeader("Accept-Ranges", "bytes")
            addMediaHeaders(resp, mime)
            return resp
        }
        val resp = newFixedLengthResponse(Response.Status.OK, mime, root.open(rel) ?: return notFound(), len)
        resp.addHeader("Accept-Ranges", "bytes")
        addMediaHeaders(resp, mime)
        return resp
    }

    private fun addMediaHeaders(resp: Response, mime: String) {
        if (mime.startsWith("audio/") || mime.startsWith("video/") || mime.startsWith("image/")) {
            resp.addHeader("transferMode.dlna.org", "Streaming")
            resp.addHeader(
                "contentFeatures.dlna.org",
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            )
        }
    }

    // ---------- DLNA 媒体端点 ----------

    private fun handleMedia(session: IHTTPSession, uri: String): Response {
        val clean = uri.removePrefix("/media").trimStart('/')
        if (clean.isEmpty()) return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_HTML, renderRootsListing())
        val dec = decode(clean)
        val seg = dec.split('/', limit = 2)
        val root = findRoot(seg[0]) ?: return notFound()
        val rel = if (seg.size > 1) seg[1] else ""
        if (!root.exists(rel) || root.isDir(rel)) return notFound()
        return serveStream(root, rel, session)
    }

    private fun handleContentDirectory(session: IHTTPSession): Response {
        val body = readBody(session)
        val action = Regex("""<u:(\w+)""").find(body)?.groupValues?.get(1) ?: return soapFault()
        return when (action) {
            "Browse" -> {
                val objectId = arg(body, "ObjectID") ?: "0"
                val (didl, count) = buildBrowse(objectId)
                val inner = "<Result>$didl</Result>" +
                    "<NumberReturned>$count</NumberReturned>" +
                    "<TotalMatches>$count</TotalMatches>" +
                    "<UpdateID>1</UpdateID>"
                soap(DlnaContent.CD_SERVICE, "Browse", inner)
            }
            "GetSearchCapabilities" ->
                soap(DlnaContent.CD_SERVICE, "GetSearchCapabilities", "<SearchCaps></SearchCaps>")
            "GetSortCapabilities" ->
                soap(DlnaContent.CD_SERVICE, "GetSortCapabilities", "<SortCaps></SortCaps>")
            "GetSystemUpdateID" ->
                soap(DlnaContent.CD_SERVICE, "GetSystemUpdateID", "<Id>1</Id>")
            else -> soapFault()
        }
    }

    private fun handleConnectionManager(session: IHTTPSession): Response {
        val body = readBody(session)
        val action = Regex("""<u:(\w+)""").find(body)?.groupValues?.get(1) ?: return soapFault()
        return when (action) {
            "GetProtocolInfo" ->
                soap(DlnaContent.CM_SERVICE, "GetProtocolInfo", "<Source>http-get:*:*:*</Source><Sink></Sink>")
            "GetCurrentConnectionIDs" ->
                soap(DlnaContent.CM_SERVICE, "GetCurrentConnectionIDs", "<ConnectionIDs></ConnectionIDs>")
            "GetCurrentConnectionInfo" ->
                soap(
                    DlnaContent.CM_SERVICE, "GetCurrentConnectionInfo",
                    "<ConnectionID>0</ConnectionID><RcsID>-1</RcsID><AvTransportID>-1</AvTransportID>" +
                        "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                        "<PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction><Status>Unknown</Status>"
                )
            else -> soapFault()
        }
    }

    // 依据 ObjectID（"0"=根列表，否则为 根名/相对路径）生成 DIDL-Lite 媒体列表。
    private fun buildBrowse(objectId: String): Pair<String, Int> {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0\">")
        var count = 0
        if (objectId == "0") {
            roots.forEach { r ->
                sb.append("<container id=\"${esc(r.name)}\" parentID=\"0\" restricted=\"1\">")
                sb.append("<dc:title>${esc(r.name)}</dc:title>")
                sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
                sb.append("</container>")
                count++
            }
            sb.append("</DIDL-Lite>")
            return sb.toString() to count
        }
        val dec = decode(objectId)
        val seg = dec.split('/', limit = 2)
        val root = findRoot(seg[0]) ?: return "" to 0
        val rel = if (seg.size > 1) seg[1] else ""
        if (!root.exists(rel) || !root.isDir(rel)) return "" to 0
        val entries = root.list(rel)
        val parentId = if (rel.isEmpty()) "0" else encode("${root.name}/$rel")
        val folders = entries.filter { it.isDir }
        val media = entries.filter { it.isDir.not() && isMedia(it.name) }
        folders.forEach { f ->
            val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            sb.append("<container id=\"${encode("${root.name}/$childRel")}\" parentID=\"$parentId\" restricted=\"1\">")
            sb.append("<dc:title>${esc(f.name)}</dc:title>")
            sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
            sb.append("</container>")
        }
        media.forEach { file ->
            val childRel = if (rel.isEmpty()) file.name else "$rel/${file.name}"
            val objId = "${root.name}/$childRel"
            val mime = mimeFor(file.name)
            val cls = mediaClass(mime)
            val url = "http://$localIp:$port/media/${encode(objId)}"
            sb.append("<item id=\"${encode(objId)}\" parentID=\"$parentId\" restricted=\"1\">")
            sb.append("<dc:title>${esc(file.name)}</dc:title>")
            sb.append("<upnp:class>$cls</upnp:class>")
            sb.append("<res protocolInfo=\"http-get:*:$mime:*\">$url</res>")
            sb.append("</item>")
        }
        sb.append("</DIDL-Lite>")
        return sb.toString() to (folders.size + media.size)
    }

    // ---------- 工具 ----------

    private fun handlePut(session: IHTTPSession, uri: String): Response {
        val clean = uri.trimStart('/')
        if (clean.isEmpty()) return badRequest("Cannot PUT root")
        val seg = clean.split('/', limit = 2)
        val root = findRoot(seg[0]) ?: return notFound()
        val rel = if (seg.size > 1) seg[1] else ""
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val buf = if (len > 0) session.inputStream.readNBytes(len.toInt()) else session.inputStream.readBytes()
        return if (root.write(rel, buf)) {
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Saved")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Write failed")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val bytes = if (len > 0) session.inputStream.readNBytes(len.toInt()) else session.inputStream.readBytes()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun arg(body: String, name: String): String? =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)

    private fun soap(serviceType: String, action: String, inner: String): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/xml; charset=utf-8",
            DlnaContent.soapEnvelope(serviceType, action, inner)
        )

    private fun soapFault(): Response {
        val inner = DlnaContent.soapEnvelope(
            DlnaContent.CD_SERVICE, "Fault",
            "<detail>Unsupported action</detail>"
        )
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml; charset=utf-8", inner)
    }

    private fun findRoot(name: String): ShareRoot? = roots.firstOrNull { it.name == name }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    private fun badRequest(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun isMedia(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus",
            "mp4", "mkv", "avi", "mov", "webm", "flv", "mpg", "mpeg", "3gp",
            "jpg", "jpeg", "png", "gif", "webp", "bmp"
        )
    }

    private fun mediaClass(mime: String): String = when {
        mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
        mime.startsWith("video/") -> "object.item.videoItem.movie"
        mime.startsWith("image/") -> "object.item.imageItem.photo"
        else -> "object.item"
    }

    private fun renderRootsListing(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<title>CarWifi 共享文件</title>")
        sb.append("<style>body{font-family:sans-serif;padding:16px}h1{font-size:18px}")
        sb.append("li{margin:4px 0}a{text-decoration:none;color:#1769ff}</style></head><body>")
        sb.append("<h1>本机共享文件</h1>")
        sb.append("<p>把车机要访问的文件放进对应目录即可（如 app 私有目录可直接上传；系统目录只读）。</p>")
        sb.append("<ul>")
        roots.forEach { r ->
            sb.append("<li>📁 <a href=\"${encode(r.name)}\">${esc(r.name)}</a></li>")
        }
        sb.append("</ul></body></html>")
        return sb.toString()
    }

    private fun renderListing(rootName: String, rel: String, entries: List<ShareEntry>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<title>CarWifi 共享文件 - $rootName</title>")
        sb.append("<style>body{font-family:sans-serif;padding:16px}h1{font-size:18px}")
        sb.append("li{margin:4px 0}a{text-decoration:none;color:#1769ff}</style></head><body>")
        sb.append("<h1>目录：$rootName${if (rel.isNotEmpty()) " / $rel" else ""}</h1>")
        sb.append("<ul>")
        entries.sortedBy { it.name.lowercase() }.forEach { e ->
            val icon = if (e.isDir) "📁" else "📄"
            val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            sb.append("<li>$icon <a href=\"${encode("$rootName/$childRel")}\">${esc(e.name)}</a>")
            if (!e.isDir) sb.append(" <small>(${e.size} B)</small>")
            sb.append("</li>")
        }
        sb.append("</ul>")
        if (roots.firstOrNull { it.name == rootName } is FileShareRoot) {
            sb.append("<hr><h2>上传文件到当前目录</h2>")
            sb.append("<input type=\"file\" id=\"file\"><button onclick=\"upload()\">上传</button>")
            sb.append("<script>function upload(){var f=document.getElementById('file').files[0];")
            sb.append("if(!f)return;var x=new XMLHttpRequest();x.open('PUT',encodeURIComponent('$rootName/$rel/'+f.name));")
            sb.append("x.send(f);x.onload=function(){location.reload();};}</script>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> MIME_OCTET
    }

    companion object {
        private const val MIME_PLAINTEXT = "text/plain; charset=utf-8"
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val MIME_OCTET = "application/octet-stream"
    }
}
