package com.carwifi.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局 Application：在进程最早时机安装「未捕获异常写文件」处理器。
 *
 * 用途：侧载 App 在真机上若发生启动期崩溃（尤其 Android 15 等较新系统），
 * 系统常只静默杀进程、无堆栈可看。此处把完整堆栈写入两个位置：
 *   1. 应用私有目录 `Android/data/com.carwifi.app/files/crash-last.txt`（adb 可取的兜底）
 *   2. 公共「下载」目录 `carwifi-crash.txt`（USB/MTP 直接拷贝，最易回传）
 *
 * 注意：处理器只负责「记录」，记录后仍交给系统默认处理器，
 * 保持原生崩溃行为（弹「应用停止」或静默退出）。
 */
class CarWifiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dir = filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val text = runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("=== CarWifi crash ===\n")
                sb.append("time=$ts sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} ")
                sb.append("model=${Build.MODEL} manufacturer=${Build.MANUFACTURER}\n")
                // printStackTrace 已包含完整 Caused by 链
                sb.append(sw.toString())
                sb.toString()
            }.getOrDefault("CarWifi crash: (堆栈序列化失败) ${throwable.message}")

            runCatching { File(dir, "crash-last.txt").writeText(text) }
            writeToDownloads(text)
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** 尽力把崩溃日志写入公共「下载」目录，便于用户经 USB 直接取出回传。 */
    private fun writeToDownloads(text: String) {
        runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "carwifi-crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
        }
    }
}
