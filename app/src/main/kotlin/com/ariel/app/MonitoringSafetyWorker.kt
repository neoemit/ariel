package com.thomaslamendola.ariel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import android.util.Log
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class MonitoringSafetyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val trustedFriendCount = prefs.getStringSet("friends", emptySet())
            ?.map { it.trim() }
            ?.count { it.isNotBlank() }
            ?: 0
        if (!MonitoringPreferences.shouldRunBackgroundMonitoring(
                backgroundMonitoringEnabled = MonitoringPreferences.isBackgroundMonitoringEnabled(prefs),
                trustedFriendCount = trustedFriendCount,
            )
        ) {
            Log.d("ArielDiagnostics", "monitoring_safety_worker skipped friends=$trustedFriendCount")
            cancel(applicationContext)
            return Result.success()
        }

        Log.d("ArielDiagnostics", "monitoring_safety_worker starting service friends=$trustedFriendCount")
        val monitorIntent = Intent(applicationContext, SirenService::class.java).apply {
            action = "START_MONITORING"
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(monitorIntent)
            } else {
                applicationContext.startService(monitorIntent)
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ariel_monitor_safety"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
            val trustedFriendCount = prefs.getStringSet("friends", emptySet())
                ?.map { it.trim() }
                ?.count { it.isNotBlank() }
                ?: 0
            if (!MonitoringPreferences.shouldRunBackgroundMonitoring(
                    backgroundMonitoringEnabled = MonitoringPreferences.isBackgroundMonitoringEnabled(prefs),
                    trustedFriendCount = trustedFriendCount,
                )
            ) {
                cancel(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<MonitoringSafetyWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
