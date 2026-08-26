package com.carwifi.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.ChannelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "carwifi_settings")

/** 全部运行配置的集合快照。 */
data class AppSettings(
    val batteryThreshold: Int = 20,
    /** 充电自动开热点开关（Shizuku 路径）。 */
    val tetheringEnabled: Boolean = true,
    val smsForwardEnabled: Boolean = true,
    val missedCallForwardEnabled: Boolean = true,
    val lowBatteryEnabled: Boolean = true,
    val hideBody: Boolean = false,
    val shizukuReady: Boolean = false,
    /** 夜间模式总开关。 */
    val nightModeEnabled: Boolean = false,
    /** 夜间模式起止小时（0-23），可跨午夜。 */
    val nightStartHour: Int = 22,
    val nightEndHour: Int = 7,
    /** 低电量是否已触发过（边沿去抖，充电回升后重置），供周期任务读取。 */
    val lowBatteryAlerted: Boolean = false,
    /** 上一周期夜间模式是否处于激活态，用于检测「解除」以触发补发。 */
    val nightModePrevActive: Boolean = false,
    /** 夜间模式期间缓存、待解除后补发的消息 JSON。 */
    val queuedMessagesJson: String = "[]",
    val channels: List<ChannelConfig> = emptyList()
) {
    /** 当前缓存待补发的消息条数。 */
    fun queuedCount(): Int = AlertQueue.parse(queuedMessagesJson).size
}

class SettingsStore(private val context: Context) {

    private val ds = context.dataStore

    val settings: Flow<AppSettings> = ds.data.map { prefs ->
        AppSettings(
            batteryThreshold = prefs[BATTERY_THRESHOLD] ?: 20,
            tetheringEnabled = prefs[TETHERING_ENABLED] ?: true,
            smsForwardEnabled = prefs[SMS_ENABLED] ?: true,
            missedCallForwardEnabled = prefs[CALL_ENABLED] ?: true,
            lowBatteryEnabled = prefs[BATTERY_ENABLED] ?: true,
            hideBody = prefs[HIDE_BODY] ?: false,
            shizukuReady = prefs[SHIZUKU_READY] ?: false,
            nightModeEnabled = prefs[NIGHT_ENABLED] ?: false,
            nightStartHour = prefs[NIGHT_START] ?: 22,
            nightEndHour = prefs[NIGHT_END] ?: 7,
            lowBatteryAlerted = prefs[LOW_ALERTED] ?: false,
            nightModePrevActive = prefs[NIGHT_PREV] ?: false,
            queuedMessagesJson = prefs[QUEUED_JSON] ?: "[]",
            channels = parseChannels(prefs[CHANNELS_JSON] ?: "[]")
        )
    }

    suspend fun update(transform: AppSettings.() -> AppSettings) {
        val current = settingsBlock()
        val next = current.transform()
        ds.edit { prefs ->
            prefs[BATTERY_THRESHOLD] = next.batteryThreshold
            prefs[TETHERING_ENABLED] = next.tetheringEnabled
            prefs[SMS_ENABLED] = next.smsForwardEnabled
            prefs[CALL_ENABLED] = next.missedCallForwardEnabled
            prefs[BATTERY_ENABLED] = next.lowBatteryEnabled
            prefs[HIDE_BODY] = next.hideBody
            prefs[SHIZUKU_READY] = next.shizukuReady
            prefs[NIGHT_ENABLED] = next.nightModeEnabled
            prefs[NIGHT_START] = next.nightStartHour
            prefs[NIGHT_END] = next.nightEndHour
            prefs[LOW_ALERTED] = next.lowBatteryAlerted
            prefs[NIGHT_PREV] = next.nightModePrevActive
            prefs[QUEUED_JSON] = next.queuedMessagesJson
            prefs[CHANNELS_JSON] = channelsToJson(next.channels)
        }
    }

    /** 同步读取当前设置快照（供首启 / 开机 / 开关回调即时使用）。 */
    suspend fun current(): AppSettings = settings.first()

    private suspend fun settingsBlock(): AppSettings = ds.data.map { prefs ->
        AppSettings(
            batteryThreshold = prefs[BATTERY_THRESHOLD] ?: 20,
            tetheringEnabled = prefs[TETHERING_ENABLED] ?: true,
            smsForwardEnabled = prefs[SMS_ENABLED] ?: true,
            missedCallForwardEnabled = prefs[CALL_ENABLED] ?: true,
            lowBatteryEnabled = prefs[BATTERY_ENABLED] ?: true,
            hideBody = prefs[HIDE_BODY] ?: false,
            shizukuReady = prefs[SHIZUKU_READY] ?: false,
            nightModeEnabled = prefs[NIGHT_ENABLED] ?: false,
            nightStartHour = prefs[NIGHT_START] ?: 22,
            nightEndHour = prefs[NIGHT_END] ?: 7,
            lowBatteryAlerted = prefs[LOW_ALERTED] ?: false,
            nightModePrevActive = prefs[NIGHT_PREV] ?: false,
            queuedMessagesJson = prefs[QUEUED_JSON] ?: "[]",
            channels = parseChannels(prefs[CHANNELS_JSON] ?: "[]")
        )
    }.first()

    companion object {
        private val BATTERY_THRESHOLD = intPreferencesKey("battery_threshold")
        private val TETHERING_ENABLED = booleanPreferencesKey("tethering_enabled")
        private val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        private val CALL_ENABLED = booleanPreferencesKey("call_enabled")
        private val BATTERY_ENABLED = booleanPreferencesKey("battery_enabled")
        private val HIDE_BODY = booleanPreferencesKey("hide_body")
        private val SHIZUKU_READY = booleanPreferencesKey("shizuku_ready")
        private val NIGHT_ENABLED = booleanPreferencesKey("night_enabled")
        private val NIGHT_START = intPreferencesKey("night_start")
        private val NIGHT_END = intPreferencesKey("night_end")
        private val LOW_ALERTED = booleanPreferencesKey("low_battery_alerted")
        private val NIGHT_PREV = booleanPreferencesKey("night_prev_active")
        private val QUEUED_JSON = stringPreferencesKey("queued_messages_json")
        private val CHANNELS_JSON = stringPreferencesKey("channels_json")

        private fun parseChannels(json: String): List<ChannelConfig> {
            val out = mutableListOf<ChannelConfig>()
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val type = ChannelType.fromKey(o.optString("type")) ?: continue
                    out += ChannelConfig(
                        id = o.optString("id"),
                        type = type,
                        name = o.optString("name"),
                        enabled = o.optBoolean("enabled", true),
                        url = o.optString("url"),
                        token = o.optString("token"),
                        template = o.optString("template", ChannelConfig.DEFAULT_TEMPLATE),
                        titleTemplate = o.optString("titleTemplate", ChannelConfig.DEFAULT_TITLE),
                        method = o.optString("method", "POST")
                    )
                }
            }
            return out
        }

        private fun channelsToJson(list: List<ChannelConfig>): String {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("type", it.type.key)
                    put("name", it.name)
                    put("enabled", it.enabled)
                    put("url", it.url)
                    put("token", it.token)
                    put("template", it.template)
                    put("titleTemplate", it.titleTemplate)
                    put("method", it.method)
                })
            }
            return arr.toString()
        }
    }
}
