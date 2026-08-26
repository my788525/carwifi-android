package com.carwifi.app.fileshare

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/** 共享根目录的一个条目（文件或文件夹）。 */
data class ShareEntry(val name: String, val isDir: Boolean, val size: Long)

/**
 * 共享根目录抽象：既可以是 App 私有目录（File），也可以是经 SAF 授权的系统目录
 * （如系统 Music / Download，通过 DocumentFile 访问，无需 MANAGE_EXTERNAL_STORAGE）。
 * 文件服务器按「根名」路由，从而把多个来源合并到同一台服务器下。
 */
interface ShareRoot {
    val name: String
    /** 列出 rel 路径下的条目（rel 为空 = 根）。 */
    fun list(rel: String): List<ShareEntry>
    /** 打开 rel 路径处的文件输入流（非文件返回 null）。 */
    fun open(rel: String): InputStream?
    /** 文件大小（非文件返回 null）。 */
    fun size(rel: String): Long?
    fun isDir(rel: String): Boolean
    fun exists(rel: String): Boolean
    /** 写入字节（多数根只读时返回 false）。 */
    fun write(rel: String, data: ByteArray): Boolean
}

/** App 私有外部存储目录（可读写，无需特殊权限）。 */
class FileShareRoot(override val name: String, private val dir: File) : ShareRoot {
    private fun resolve(rel: String): File {
        val f = File(dir, rel.trimStart('/'))
        return if (!f.canonicalPath.startsWith(dir.canonicalPath)) dir else f
    }

    override fun list(rel: String): List<ShareEntry> {
        val d = resolve(rel)
        if (!d.isDirectory) return emptyList()
        return d.listFiles()?.sortedBy { it.name.lowercase() }?.map {
            ShareEntry(it.name, it.isDirectory, it.length())
        } ?: emptyList()
    }

    override fun open(rel: String): InputStream? {
        val f = resolve(rel)
        return if (f.isFile) f.inputStream() else null
    }

    override fun size(rel: String): Long? = resolve(rel).takeIf { it.isFile }?.length()
    override fun isDir(rel: String): Boolean = resolve(rel).isDirectory
    override fun exists(rel: String): Boolean = resolve(rel).exists()
    override fun write(rel: String, data: ByteArray): Boolean = runCatching {
        val f = resolve(rel)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
        true
    }.getOrDefault(false)
}

/** 经 SAF 授权的系统目录（如系统 Music / Download）。只读媒体也足够车载播放。 */
class DocShareRoot(
    override val name: String,
    private val treeUri: Uri,
    private val context: Context
) : ShareRoot {
    private fun rootDoc(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    private fun resolve(rel: String): DocumentFile? {
        var cur = rootDoc() ?: return null
        val segs = rel.trim('/').split('/').filter { it.isNotBlank() }
        for (s in segs) {
            cur = cur.findFile(s) ?: return null
        }
        return cur
    }

    override fun list(rel: String): List<ShareEntry> {
        val d = resolve(rel) ?: return emptyList()
        if (!d.isDirectory) return emptyList()
        return d.listFiles().map { ShareEntry(it.name ?: "", it.isDirectory, it.length()) }
    }

    override fun open(rel: String): InputStream? {
        val f = resolve(rel) ?: return null
        if (!f.isFile) return null
        return runCatching { context.contentResolver.openInputStream(f.uri) }.getOrNull()
    }

    override fun size(rel: String): Long? = resolve(rel)?.takeIf { it.isFile }?.length()
    override fun isDir(rel: String): Boolean = resolve(rel)?.isDirectory ?: false
    override fun exists(rel: String): Boolean = resolve(rel)?.exists() ?: false
    override fun write(rel: String, data: ByteArray): Boolean {
        val parent = resolve(rel.substringBeforeLast('/', "")) ?: rootDoc() ?: return false
        val fname = rel.substringAfterLast('/')
        val target = parent.createFile("*/*", fname) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(target.uri)?.use { it.write(data) } != null
        }.getOrDefault(false)
    }
}
