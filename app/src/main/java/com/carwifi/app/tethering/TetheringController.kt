package com.carwifi.app.tethering

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

/**
 * 热点控制器。
 *
 * ⚠️ 普通应用（无 Root / 无 Shizuku）无法用公开或隐藏 API 直接开关热点。
 * 已移除 Shizuku 依赖，startHotspot / stopHotspot 恒返回 false，由 UI 引导用户在
 * 系统设置手动开启热点，或使用厂商自带「自动任务 / 情景智能」实现充电自动开热点。
 * isHotspotOn 仅做状态查询（反射 getTetheredIfaces，无需特殊权限），用于协调文件共享起停。
 */
class TetheringController(private val context: Context) {

    companion object {
        private const val TAG = "TetheringController"
    }

    /** 程序化开热点不可用（无系统权限）。返回 false，调用方据此提示用户手动开启。 */
    fun startHotspot(): Boolean {
        Log.i(TAG, "startHotspot: 本应用无法自动开热点（无系统权限），请在系统设置手动开启")
        return false
    }

    /** 程序化关热点不可用。返回 false。 */
    fun stopHotspot(): Boolean {
        Log.i(TAG, "stopHotspot: 本应用无法自动关热点，请在系统设置手动关闭")
        return false
    }

    /** 当前 WiFi 热点是否处于开启状态（反射 getTetheredIfaces，无需特殊权限）。 */
    fun isHotspotOn(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val m = cm.javaClass.getMethod("getTetheredIfaces")
        val arr = m.invoke(cm) as? Array<*>
        arr?.any { it is String && (it.contains("wlan", ignoreCase = true) || it.contains("ap", ignoreCase = true)) }
            ?: false
    }.getOrDefault(false)

    /** 降级方案：打开系统热点设置页，供用户手动开启。 */
    fun openTetherSettings() {
        runCatching {
            // 等价于 Settings.ACTION_TETHER_SETTINGS，避免对平台常量的编译期依赖
            context.startActivity(Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
