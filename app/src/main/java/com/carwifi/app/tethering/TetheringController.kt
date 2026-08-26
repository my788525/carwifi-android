package com.carwifi.app.tethering

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors

/**
 * 热点控制器。
 *
 * ⚠️ Android 10+ 限制：普通应用无法用公开 API 直接开热点。
 * 本实现通过 Shizuku 获取 IConnectivityManager 的 binder，再用反射调用隐藏的
 * startTethering / stopTethering。前提：用户已在 Shizuku 中授权本应用。
 * 若 Shizuku 不可用，则降级为跳转到系统热点设置页（需用户手动开启）。
 */
class TetheringController(private val context: Context) {

    companion object {
        private const val TAG = "TetheringController"
        private const val TETHERING_WIFI = 0 // ConnectivityManager.TETHERING_WIFI
    }

    /** Shizuku 是否已就绪（已连接且已授权）。 */
    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0 // 0 == PERMISSION_GRANTED
    }.getOrDefault(false)

    fun startHotspot(): Boolean {
        if (!isReady()) return false
        return runCatching {
            val binder = SystemServiceHelper.getSystemService("connectivity")
            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            val proxy = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            invokeStart(proxy, TETHERING_WIFI)
        }.onFailure { Log.e(TAG, "startHotspot failed", it) }.getOrDefault(false)
    }

    /** 当前 WiFi 热点是否处于开启状态（反射 getTetheredIfaces，无需 Shizuku 权限）。 */
    fun isHotspotOn(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val m = cm.javaClass.getMethod("getTetheredIfaces")
        val arr = m.invoke(cm) as? Array<*>
        arr?.any { it is String && (it.contains("wlan", ignoreCase = true) || it.contains("ap", ignoreCase = true)) }
            ?: false
    }.getOrDefault(false)

    fun stopHotspot(): Boolean {
        if (!isReady()) return false
        return runCatching {
            val binder = SystemServiceHelper.getSystemService("connectivity")
            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            val proxy = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            val m = proxy.javaClass.getMethod("stopTethering", Int::class.java)
            m.invoke(proxy, TETHERING_WIFI)
            true
        }.onFailure { Log.e(TAG, "stopHotspot failed", it) }.getOrDefault(false)
    }

    /** 降级方案：打开系统热点设置页。 */
    fun openTetherSettings() {
        runCatching {
            // 等价于 Settings.ACTION_TETHER_SETTINGS，避免对平台常量的编译期依赖
            context.startActivity(Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun invokeStart(proxy: Any, type: Int): Boolean {
        val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
        val executor = Executors.newSingleThreadExecutor()

        // 候选签名：不同 Android 版本形参顺序不同，依次尝试。
        val variants = listOf(
            // API 30+: startTethering(int, Executor, OnStartTetheringCallback, boolean)
            listOf(
                Int::class.java,
                java.util.concurrent.Executor::class.java,
                callbackClass,
                Boolean::class.java
            ) to { m: java.lang.reflect.Method ->
                m.invoke(proxy, type, executor, null, false)
            },
            // API 24-29: startTethering(int, boolean, OnStartTetheringCallback, Handler)
            listOf(
                Int::class.java,
                Boolean::class.java,
                callbackClass,
                Handler::class.java
            ) to { m: java.lang.reflect.Method ->
                m.invoke(proxy, type, false, null, null)
            }
        )

        for ((sig, call) in variants) {
            try {
                val m = proxy.javaClass.getMethod("startTethering", *sig.toTypedArray())
                call(m)
                return true
            } catch (e: NoSuchMethodException) {
                // 尝试下一种签名
            } catch (e: Exception) {
                Log.e(TAG, "invokeStart error", e)
                return true // 已发出调用，视为已尝试
            }
        }
        return false
    }
}
