package com.carwifi.app.workers

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 调度周期监测任务。唯一命名，重复调用只会更新而不会叠加。
 * 间隔设为 15 分钟（WorkManager 允许的最短周期），兼顾省电与及时性。
 */
object MonitorScheduler {
    private const val NAME = "carwifi_monitor"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
