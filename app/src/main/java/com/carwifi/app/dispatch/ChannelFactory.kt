package com.carwifi.app.dispatch

import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.ChannelType

/** 根据配置构建对应渠道实例；配置不完整返回 null（将被跳过）。 */
object ChannelFactory {
    fun build(config: ChannelConfig): Channel? = when (config.type) {
        ChannelType.BARK -> if (config.token.isNotBlank() || config.url.isNotBlank()) BarkChannel(config) else null
        ChannelType.SERVER_CHAN -> if (config.token.isNotBlank()) ServerChanChannel(config) else null
        ChannelType.WECOM -> if (config.token.isNotBlank()) WeComChannel(config) else null
        ChannelType.WEBHOOK -> if (config.url.isNotBlank()) CustomWebhookChannel(config) else null
    }
}
