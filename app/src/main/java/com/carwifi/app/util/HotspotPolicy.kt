package com.carwifi.app.util

import com.carwifi.app.data.AppSettings

/**
 * 热点控制策略：判断「是否由本应用接管热点」。
 *
 * 已移除 Shizuku 依赖：普通应用（无 Root / 无 Shizuku）无法程序化控制热点，
 * 因此本方法恒返回 false。热点由用户在系统设置手动开启，或借助厂商自带
 * 「自动任务 / 情景智能」（如小米：充电时自动开热点）实现免手动。
 */
object HotspotPolicy {
    fun shouldControlByApp(s: AppSettings): Boolean = false
}
