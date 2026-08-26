package com.carwifi.app.model

/**
 * 单个接口配置（预置在 GitHub 的 channels-catalog.json 中）。
 * 一个推送渠道（ChannelConfig）可挂载多个接口配置，消息会发往全部接口。
 *
 * 字段含义：
 * - bark:      server = 服务器地址（可空，默认官方 api.day.app），token = 设备/推送 key
 * - wxpusher:  token = appToken，extra = 接收目标（UID 或 Topic ID，详见 WxPusherChannel）
 * - pushplus:  token = 用户 token，extra = 群组编码 topic（可选，留空仅发给自己）
 * - wecom:     token = webhook key（server 留空）
 * - webhook:   server = 完整端点，method = GET/POST，token 留空
 *
 * extra：渠道特定的附加参数（JSON 字符串），目前 WxPusher 用其承载 UID/Topic、PushPlus 用其承载群组编码。
 */
data class PresetInterface(
    val id: String = "",
    val name: String = "",
    val type: ChannelType,
    val server: String = "",
    val token: String = "",
    val method: String = "POST",
    val template: String = ChannelConfig.DEFAULT_TEMPLATE,
    val titleTemplate: String = ChannelConfig.DEFAULT_TITLE,
    val extra: String = ""
)
