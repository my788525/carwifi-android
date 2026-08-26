package com.carwifi.app.dlna

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 生成设备稳定的 UPnP UDN（UUID）。
 * 基于 Android ID 派生，设备重装/重启保持不变，便于车机媒体库记忆播放进度与收藏。
 */
object DeviceUuid {
    fun get(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "carwifi-default"
        val hex = MessageDigest.getInstance("MD5")
            .digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
