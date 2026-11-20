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

    /**
     * Rules derived from the active focus profiles (see [ProfileEnforcer]): per-package
     * rules for the profile's apps (Blocklist) or for every other launcher app
     * (Whitelist), plus the motivational text per package and the Combined usage
     * limit. Refreshed by the accessibility service from `profileDao().observeActive()`.
     * Profile rules never set `blockDuringLock` (see ProfileEnforcer).
     */
    @Volatile var profileBlocked: Map<String, BlockedApplication> = emptyMap()
    @Volatile var profileMotivations: Map<String, String> = emptyMap()
    @Volatile var combinedLimit: ProfileEnforcer.CombinedLimit? = null
    @Volatile var profileSiteRules: List<ProfileEnforcer.SiteRule> = emptyList()

    /** Usage minutes recorded when each app's current wait was armed (Wait Timer state, this process). */
    private val waitArmedUsageMin = ConcurrentHashMap<String, Int>()

    /** Phone, emergency, system UI and launcher stay reachable at all times, whatever the user selects. */
    private val neverBlock: Set<String> by lazy { buildNeverBlock() }

    /**
     * Decides whether opening an app should be blocked. An explicit per-app rule is
     * checked first (its "App Launch" off still wins over a profile); then the
     * active profile's rule for this app; then the Combined usage limit.
     */
    fun reason(packageName: String): BlockReason? {
        if (packageName in neverBlock || packageName in allowed) return null
        val now = System.currentTimeMillis()
        val rule = blocked[packageName]
        if (rule != null) {
            // "I Want to Block: App Launch" off means launches are never blocked, lock or not.
            if (!rule.blockLaunch) return null
            if (lockBlocksApps && rule.blockDuringLock) return BlockReason.LOCK
            ConditionEvaluator.evaluate(rule, gatherFacts(rule, now))?.let { return it }
        }
        val profileRule = profileBlocked[packageName]
        if (profileRule != null && profileRule.blockLaunch) {
            ConditionEvaluator.evaluate(profileRule, gatherFacts(profileRule, now))?.let { return it }
        }
        combinedLimit?.let { limit ->
            if (packageName in limit.packages && limit.limitMs > 0) {
                val totalMs = limit.packages.sumOf { usage.todayUsageMs(it) }
                if (totalMs >= limit.limitMs) return BlockReason.DAILY_LIMIT
            }
        }
        return null
    }

    /** The motivational text of the profile blocking this app, if any. */
    fun motivationFor(packageName: String): String? = profileMotivations[packageName]

    /**
     * Evaluates a profile's site rule for a host opened in the foreground browser:
     * usage facts are gathered for the browser package (the meter for a Usage Limit
     * profile's sites), location/time facts as usual.
     */
    fun evaluateSiteRule(rule: BlockedApplication, foregroundPkg: String): BlockReason? {
        val factsRule = rule.copy(packageName = foregroundPkg)
        return ConditionEvaluator.evaluate(rule, gatherFacts(factsRule, System.currentTimeMillis()))
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

    /**
     * Starts the Wait Timer for a profile-derived rule. In-memory only — unlike a
     * per-app rule it is never written to `blocked_application` (a profile rule
     * must not become a standalone app rule that outlives the profile). If the
     * service restarts the wait re-arms on next use (fail-closed, as documented
     * for wait timers).
     */
    fun armProfileWaitTimer(packageName: String, nowMs: Long = System.currentTimeMillis()): BlockedApplication? {
        val rule = profileBlocked[packageName] ?: return null
        if (rule.waitAfterMinutes <= 0 || rule.waitForMinutes <= 0) return null
        val usedMin = (usage.todayUsageMs(packageName) / 60_000L).toInt()
        val armed = ConditionEvaluator.armWaitTimer(rule, usedMin, nowMs)
        waitArmedUsageMin[packageName] = usedMin
        profileBlocked = profileBlocked + (packageName to armed)
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
