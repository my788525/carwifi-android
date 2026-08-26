package com.carwifi.app.dispatch

import com.carwifi.app.model.ChannelType
import com.carwifi.app.model.PresetInterface

/** 根据接口配置构建对应渠道实例；配置不完整返回 null（将被跳过）。 */
object ChannelFactory {
    fun build(config: PresetInterface): Channel? = when (config.type) {
        ChannelType.BARK -> if (config.token.isNotBlank() || config.server.isNotBlank()) BarkChannel(config) else null
        ChannelType.WXPUSHER -> if (config.token.isNotBlank() && config.extra.isNotBlank()) WxPusherChannel(config) else null
        ChannelType.PUSHPLUS -> if (config.token.isNotBlank()) PushPlusChannel(config) else null
        ChannelType.WECOM -> if (config.token.isNotBlank()) WeComChannel(config) else null
        ChannelType.WEBHOOK -> if (config.server.isNotBlank()) CustomWebhookChannel(config) else null
    }
}
