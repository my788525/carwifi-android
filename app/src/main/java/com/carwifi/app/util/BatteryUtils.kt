package com.carwifi.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 充电状态与电量读取。
 * 通过查询系统电池广播快照（非注册监听），零持续功耗，供开机自启与热点保活使用。
 */
object BatteryUtils {

    /** 当前是否处于充电状态（交流 / USB / 无线）。 */
    fun isCharging(context: Context): Boolean {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
            || plugged == BatteryManager.BATTERY_PLUGGED_USB
            || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    /** 当前电量百分比（0-100），读取失败返回 -1。 */
    fun level(context: Context): Int {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return -1
        val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
    }
}
