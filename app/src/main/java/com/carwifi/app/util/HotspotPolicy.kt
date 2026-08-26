package com.carwifi.app.util

import com.carwifi.app.data.AppSettings

/**
 * 热点控制策略：判断「是否由本应用经 Shizuku 接管热点」。
 * - 小米 / Redmi / POCO：系统自带「自动任务」可充电开热点，无需应用接管；
 *   仅在用户已授权 Shizuku 时才由应用接管（避免无谓失败与日志噪音）。
 * - 其他机型：必须由本应用经 Shizuku 控制。
 */
object HotspotPolicy {
    fun shouldControlByApp(s: AppSettings): Boolean = !DeviceUtils.isXiaomi() || s.shizukuReady
}
