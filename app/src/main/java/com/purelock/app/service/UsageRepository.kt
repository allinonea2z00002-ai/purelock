package com.purelock.app.service

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** App usage for daily limits, read from Android's UsageStats (needs the user's "Usage access" grant). */
class UsageRepository(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val cache = ConcurrentHashMap<String, Pair<Long, Long>>() // package -> (read at, usage ms)

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground time today in milliseconds (cached for 30 seconds per app). Returns 0 without usage access. */
    fun todayUsageMs(packageName: String): Long {
        if (!hasAccess()) return 0L
        val now = System.currentTimeMillis()
        cache[packageName]?.let { (at, ms) -> if (now - at < 30_000) return ms }
        val usage = readToday(now)[packageName] ?: 0L
        cache[packageName] = now to usage
        return usage
    }

    /** Today's usage for every app, for display on the app-management screen. */
    fun allToday(): Map<String, Long> = if (hasAccess()) readToday(System.currentTimeMillis()) else emptyMap()

    private fun readToday(now: Long): Map<String, Long> {
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return usm.queryAndAggregateUsageStats(startOfDay, now)
            .mapValues { it.value.totalTimeInForeground }
    }
}
