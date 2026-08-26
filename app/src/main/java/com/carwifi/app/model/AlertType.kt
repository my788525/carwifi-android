package com.carwifi.app.model

/** 事件类型，决定模板中的 {{event}} 文案与默认标题。 */
enum class AlertType(val key: String, val label: String) {
    SMS("sms", "新短信"),
    MISSED_CALL("missed_call", "未接来电"),
    LOW_BATTERY("low_battery", "低电量提醒");

    companion object {
        fun fromKey(key: String): AlertType? = entries.firstOrNull { it.key == key }
    }
}
