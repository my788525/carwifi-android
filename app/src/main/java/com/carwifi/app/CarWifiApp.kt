package com.carwifi.app

import android.app.Application
import android.os.Build
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
 * 系统常只静默杀进程、无堆栈可看。此处把完整堆栈写入
 * `Android/data/com.carwifi.app/files/crash-last.txt`，便于用户回传定位。
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
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("=== CarWifi crash ===\n")
                sb.append("time=$ts sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} ")
                sb.append("model=${Build.MODEL} manufacturer=${Build.MANUFACTURER}\n")
                // printStackTrace 已包含完整 Caused by 链
                sb.append(sw.toString())
                File(dir, "crash-last.txt").writeText(sb.toString())
            }
            prev?.uncaughtException(thread, throwable)
        }
    }
}
