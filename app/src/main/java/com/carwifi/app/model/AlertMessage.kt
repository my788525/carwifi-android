package com.carwifi.app.model

/**
 * 统一消息模型：短信 / 未接来电 / 低电量 都归一化为此对象，
 * 再由 MessageDispatcher 渲染模板并发往各渠道。
 */
data class AlertMessage(
    val type: AlertType,
    val sender: String = "",
    val body: String = "",
    val battery: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 人类可读的时间字符串，供模板占位符使用。 */
    fun timeText(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    fun deviceName(): String = android.os.Build.MODEL ?: "Android"
}
