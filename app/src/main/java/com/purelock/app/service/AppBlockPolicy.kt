package com.purelock.app.service

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.data.db.SafeZone
import com.purelock.app.location.Fix
import com.purelock.app.location.LocationHelper
import java.util.concurrent.ConcurrentHashMap

enum class BlockReason {
    LOCK, DAILY_LIMIT, HOURLY_LIMIT, TIME_INTERVAL, QUICK_BLOCK, LAUNCH_LIMIT, WAIT_TIMER, GOAL,
    LOCATION, SELF_PROTECT
}

/**
 * Decides whether opening an app should be blocked. The caches are refreshed from the database by the
 * accessibility service, so decisions on the UI thread never touch the database.
 *
 * New caches the service must refresh alongside [blocked] (same combine block):
 * - [zones]: all SafeZones, by id, for the location condition.
 * - [lastFix]: the latest location fix (from LocationHelper or the last LocationRecord). A stale
 *   fix is fine: the location gate uses it as-is, and a null fix means "location unavailable".
 * The service also arms Wait Timers: when [reason] returns [BlockReason.WAIT_TIMER] for a rule
 * whose waitUntilEpochMs is 0 (or expired), call [armWaitTimer] and persist the returned rule
 * with BlockedAppDao.upsert, so the wait gets an end time.
 */
class AppBlockPolicy(private val context: Context, private val usage: UsageRepository) {

    @Volatile var lockBlocksApps: Boolean = false
    @Volatile var blocked: Map<String, BlockedApplication> = emptyMap()
    @Volatile var allowed: Set<String> = emptySet()
    @Volatile var zones: Map<Long, SafeZone> = emptyMap()
    @Volatile var lastFix: Fix? = null

    /** Usage minutes recorded when each app's current wait was armed (Wait Timer state, this process). */
    private val waitArmedUsageMin = ConcurrentHashMap<String, Int>()

    /** Phone, emergency, system UI and launcher stay reachable at all times, whatever the user selects. */
    private val neverBlock: Set<String> by lazy { buildNeverBlock() }

    fun reason(packageName: String): BlockReason? {
        if (packageName in neverBlock || packageName in allowed) return null
        val rule = blocked[packageName] ?: return null
        // "I Want to Block: App Launch" off means launches are never blocked, lock or not.
        if (!rule.blockLaunch) return null
        if (lockBlocksApps && rule.blockDuringLock) return BlockReason.LOCK
        val now = System.currentTimeMillis()
        return ConditionEvaluator.evaluate(rule, gatherFacts(rule, now))
    }

    /**
     * Starts the Wait Timer for an app: returns the rule with waitUntilEpochMs set, and remembers
     * the usage the wait was armed at. Returns null when the app has no wait-timer rule. The
     * caller must persist the returned rule; until then the app stays blocked.
     */
    fun armWaitTimer(packageName: String, nowMs: Long = System.currentTimeMillis()): BlockedApplication? {
        val rule = blocked[packageName] ?: return null
        if (rule.waitAfterMinutes <= 0 || rule.waitForMinutes <= 0) return null
        val usedMin = (usage.todayUsageMs(packageName) / 60_000L).toInt()
        val armed = ConditionEvaluator.armWaitTimer(rule, usedMin, nowMs)
        waitArmedUsageMin[packageName] = usedMin
        blocked = blocked + (packageName to armed)
        return armed
    }

    private fun gatherFacts(rule: BlockedApplication, now: Long): ConditionEvaluator.Facts {
        val zone = if (rule.locationZoneId > 0) zones[rule.locationZoneId] else null
        val fix = lastFix
        val distance: Float? = if (zone != null && fix != null) {
            LocationHelper.distanceMeters(zone.latitude, zone.longitude, fix.latitude, fix.longitude)
        } else {
            null
        }
        val goalPkg = rule.goalAppPackage
        return ConditionEvaluator.Facts(
            nowMs = now,
            usageTodayMs = usage.todayUsageMs(rule.packageName),
            usageThisHourMs = usage.usageThisHourMs(rule.packageName),
            launchesToday = usage.launchesToday(rule.packageName),
            launchesThisHour = usage.launchesThisHour(rule.packageName),
            goalUsageTodayMs = if (goalPkg != null) usage.todayUsageMs(goalPkg) else 0L,
            distanceToZoneM = distance,
            zoneRadiusM = zone?.radiusM ?: 0,
            waitArmedUsageMin = waitArmedUsageMin[rule.packageName]
        )
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
