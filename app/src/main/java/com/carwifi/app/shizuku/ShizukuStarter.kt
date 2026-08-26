package com.carwifi.app.shizuku

import android.app.Activity
import rikka.shizuku.Shizuku

/**
 * Shizuku 初始化与权限请求封装。
 * 必须在已安装并运行 Shizuku 的环境中使用；首次需用户在 Shizuku 中授权本应用。
 */
object ShizukuStarter {
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null
    private var binderListener: Shizuku.OnBinderReceivedListener? = null

    /** 初始化（建议在 Application/Activity 中调用一次）。 */
    fun init() {
        if (binderListener == null) {
            binderListener = Shizuku.OnBinderReceivedListener { /* binder 已就绪 */ }
            Shizuku.addBinderReceivedListenerSticky(binderListener!!)
        }
    }

    /** Shizuku 已连接且已授权。 */
    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    }.getOrDefault(false)

    /** 请求 Shizuku 权限；结果通过 onResult 回调。 */
    fun requestPermission(activity: Activity, code: Int, onResult: (granted: Boolean) -> Unit) {
        if (isReady()) { onResult(true); return }
        permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == code) onResult(grantResult == 0)
        }
        Shizuku.addRequestPermissionResultListener(permissionListener!!)
        Shizuku.requestPermission(code)
    }
}
