package com.purelock.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.purelock.app.Graph
import com.purelock.app.domain.LockMode
import com.purelock.app.location.LocationHelper
import com.purelock.app.vpn.PureLockVpnService
import java.util.concurrent.TimeUnit

/** WorkManager scheduling. Work survives process death and reboot (WorkManager re-registers it). */
object WorkScheduler {
    private const val PERIODIC = "purelock_guard"
    private const val UNLOCK = "purelock_unlock"

    /** Every 15 minutes (the Android minimum): checkpoint, location sample, integrity check. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One-shot work near the expected end of a time lock, so it ends without needing the app open. */
    fun scheduleUnlock(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<UnlockWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNLOCK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelUnlock(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNLOCK)
    }
}

class GuardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = Graph.lockManager.checkpoint()

        if (cfg != null && cfg.active) {
            if (LockMode.fromName(cfg.mode).usesDistance) {
                val fix = LocationHelper.currentFix(applicationContext, highAccuracy = false, timeoutMs = 20_000)
                if (fix != null) {
                    Graph.lockManager.onLocation(fix.latitude, fix.longitude, fix.accuracyM, "WORKER")
                }
            }
            Graph.integrity.evaluateAndNotify()
        } else {
            // No lock: still keep a user-enabled adult filter alive when Android allows it.
            val settings = Graph.settings.get()
            if (settings.filterEnabled && !PureLockVpnService.isRunning()) {
                if (android.net.VpnService.prepare(applicationContext) == null) {
                    PureLockVpnService.start(applicationContext)
                }
            }
        }
        return Result.success()
    }
}

class UnlockWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.lockManager.checkpoint()
        return Result.success()
    }
}
