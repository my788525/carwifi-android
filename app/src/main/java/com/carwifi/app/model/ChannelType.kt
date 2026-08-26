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
