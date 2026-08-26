package com.carwifi.app.data

import com.carwifi.app.model.AlertMessage
import com.carwifi.app.model.AlertType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 夜间模式缓存消息的 JSON 序列化工具。
 * 直接落在 AppSettings.queuedMessagesJson 字符串中，避免引入额外 DataStore。
 */
object AlertQueue {

    fun parse(json: String): List<AlertMessage> {
        val out = mutableListOf<AlertMessage>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = AlertType.fromKey(o.optString("type")) ?: continue
                out += AlertMessage(
                    type = type,
                    sender = o.optString("sender"),
                    body = o.optString("body"),
                    battery = if (o.has("battery")) o.optInt("battery", -1).let { if (it < 0) null else it } else null,
                    timestamp = o.optLong("timestamp", System.currentTimeMillis())
                )
            }
        }
        return out
    }

    fun toJson(list: List<AlertMessage>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("type", it.type.key)
                put("sender", it.sender)
                put("body", it.body)
                put("battery", it.battery ?: JSONObject.NULL)
                put("timestamp", it.timestamp)
            })
        }
        return arr.toString()
    }
}
