package com.purelock.app.service

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.purelock.app.data.db.BlockedApplication

enum class BlockReason { LOCK, DAILY_LIMIT, SELF_PROTECT }

/**
 * Decides whether opening an app should be blocked. The caches are refreshed from the database by the
 * accessibility service, so decisions on the UI thread never touch the database.
 */
class AppBlockPolicy(private val context: Context, private val usage: UsageRepository) {

    @Volatile var lockBlocksApps: Boolean = false
    @Volatile var blocked: Map<String, BlockedApplication> = emptyMap()
    @Volatile var allowed: Set<String> = emptySet()

    /** Phone, emergency, system UI and launcher stay reachable at all times, whatever the user selects. */
    private val neverBlock: Set<String> by lazy { buildNeverBlock() }

    fun reason(packageName: String): BlockReason? {
        if (packageName in neverBlock || packageName in allowed) return null
        val rule = blocked[packageName] ?: return null
        if (lockBlocksApps && rule.blockDuringLock) return BlockReason.LOCK
        if (rule.dailyLimitMinutes > 0 &&
            usage.todayUsageMs(packageName) >= rule.dailyLimitMinutes * 60_000L
        ) return BlockReason.DAILY_LIMIT
        return null
    }

    fun isNeverBlocked(packageName: String) = packageName in neverBlock

    private fun buildNeverBlock(): Set<String> {
        val pm = context.packageManager
        val set = hashSetOf(
            context.packageName,
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer"
        )
        runCatching {
            context.getSystemService(Context.TELECOM_SERVICE)
                .let { it as? TelecomManager }?.defaultDialerPackage?.let(set::add)
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        return set
    }
}
