package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.ChannelConfig

/** 推送渠道抽象：新增渠道只需实现此接口。 */
interface Channel {
    val config: ChannelConfig
    suspend fun send(msg: AlertMessage): Result<Unit>
}
