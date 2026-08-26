package com.carwifi.app.util

import com.carwifi.app.data.AppSettings
import java.util.Calendar

/**
 * 夜间模式（安静时段）判定。
 * 窗口可跨午夜：如 22 → 7 表示 22:00 至次日 07:00。
 */
object NightModeManager {

    fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** 给定起止小时与当前小时，判断是否处于夜间窗口内。 */
    fun isActive(startHour: Int, endHour: Int, h: Int): Boolean {
        if (startHour == endHour) return false
        return if (startHour < endHour) h in startHour until endHour
        else (h >= startHour || h < endHour)
    }

    /** 根据当前设置判断此刻是否处于夜间模式（需总开关开启）。 */
    fun isActive(s: AppSettings): Boolean =
        s.nightModeEnabled && isActive(s.nightStartHour, s.nightEndHour, currentHour())
}
