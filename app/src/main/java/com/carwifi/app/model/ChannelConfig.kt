package com.carwifi.app.model

/**
 * 单个推送渠道（用户在 App 内创建）。
 * 一个渠道可挂载多个接口配置（interfaces）——消息会发往全部接口。
 * 接口配置（PresetInterface）的 key/token/服务器地址/body 模板 预置在 GitHub 的
 * channels-catalog.json，用户只需勾选。本对象的 defaultTemplate/defaultTitleTemplate
 * 作为接口未指定模板时的兜底。
 */
data class ChannelConfig(
    val id: String,
    val name: String,
    val type: ChannelType,
    val enabled: Boolean,
    val interfaces: List<PresetInterface> = emptyList(),
    val defaultTemplate: String = DEFAULT_TEMPLATE,
    val defaultTitleTemplate: String = DEFAULT_TITLE
) {
    companion object {
        const val DEFAULT_TEMPLATE = "{{event}}\n来自 {{sender}}：{{body}}\n时间 {{time}}"
        const val DEFAULT_TITLE = "{{event}} · {{device}}"
    }
}
