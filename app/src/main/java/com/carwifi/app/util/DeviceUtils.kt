package com.carwifi.app.util

import android.os.Build

/**
 * 设备/ROM 检测。
 * 小米/Redmi/POCO（MIUI/HyperOS）系统自带「自动任务」，可实现充电自动开热点，
 * 因此本应用无需对其进行 Shizuku 操作引导——仅在朋友的非自带机型上给出完整引导。
 * 注意：仅依据 Build 字段判断（不调用隐藏 API，避免兼容性问题）。
 */
object DeviceUtils {

    fun isXiaomi(): Boolean {
        val keys = listOf(
            Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.PRODUCT
        ).map { it.orEmpty().lowercase() }
        return keys.any {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") || it.contains("mi ")
        }
    }
}
