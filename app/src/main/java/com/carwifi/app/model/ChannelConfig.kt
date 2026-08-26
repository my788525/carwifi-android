package com.carwifi.app.model

/** 支持的推送渠道类型。 */
enum class ChannelType(val key: String, val label: String) {
    BARK("bark", "Bark"),
    SERVER_CHAN("server_chan", "Server 酱"),
    WECOM("wecom", "企业微信机器人"),
    WEBHOOK("webhook", "自定义 Webhook");

    companion object {
        fun fromKey(key: String): ChannelType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 单个渠道配置。
 * - bark:      url = 服务器地址(可空，默认官方 api.day.app)，token = 设备/推送 key
 * - server_chan: url 留空，token = SCT 后的 key
 * - wecom:     url 留空，token = webhook key
 * - webhook:   url = 完整端点，token 留空；method 支持 GET/POST
 */
data class ChannelConfig(
    val id: String,
    val type: ChannelType,
    val name: String,
    val enabled: Boolean,
    val url: String,
    val token: String,
    val template: String,
    val titleTemplate: String = "",
    val method: String = "POST"
) {
    companion object {
        const val DEFAULT_TEMPLATE = "{{event}}\n来自 {{sender}}：{{body}}\n时间 {{time}}"
        const val DEFAULT_TITLE = "{{event}} · {{device}}"
    }
}
