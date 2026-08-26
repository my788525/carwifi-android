package com.carwifi.app.dispatch

import com.carwifi.app.model.AlertMessage

/** 模板占位符渲染：支持 {{event}} {{sender}} {{body}} {{time}} {{battery}} {{device}}。 */
object TemplateRenderer {
    fun render(template: String, msg: AlertMessage): String = template
        .replace("{{event}}", msg.type.label)
        .replace("{{sender}}", msg.sender.ifEmpty { "未知" })
        .replace("{{body}}", msg.body)
        .replace("{{time}}", msg.timeText())
        .replace("{{battery}}", msg.battery?.toString() ?: "?")
        .replace("{{device}}", msg.deviceName())
}
