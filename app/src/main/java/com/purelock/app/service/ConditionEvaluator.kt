package com.purelock.app.service

import com.purelock.app.data.db.BlockedApplication
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The pure core of [AppBlockPolicy]: given a rule and the already-gathered facts, decide whether
 * the app is blocked and why. No Android calls, no database, no clock reads of its own — [nowMs]
 * is passed in — so this is covered by plain unit tests ([AppBlockPolicyTest]).
 *
 * Enforcement keys off the configured *values*, not [BlockedApplication.conditionType] (which is
 * display state from the chooser): an old rule with conditionType NONE but a daily limit still
 * blocks on that limit, and a rule blocks when *every* configured gate says so — the first firing
 * gate, in the order below, is the reported reason.
 *
 * Gates, in evaluation order:
 * 1. Usage Limit — daily limit, then hourly limit.
 * 2. Quick Block — blocked until [BlockedApplication.quickBlockUntilEpochMs].
 * 3. Number of Launches — per-day, then per-hour cap.
 * 4. Wait Timer — after each [BlockedApplication.waitAfterMinutes] chunk of use, blocked for
 *    [BlockedApplication.waitForMinutes]; see [armWaitTimer].
 * 5. Goal Based — blocked until [BlockedApplication.goalMinutes] were spent on
 *    [BlockedApplication.goalAppPackage] today.
 * 6. Specific Time Intervals — blocked only inside the interval, on the selected days.
 * 7. Location — blocked while inside (AT) or outside (AWAY) the zone; with
 *    [BlockedApplication.blockIfLocationUnavailable] a missing fix keeps the app blocked.
 *
 * The lock gate and the never-block/allowlist checks live in [AppBlockPolicy.reason], which
 * calls this after them.
 */
object ConditionEvaluator {

    const val DAY_MON = 1
    const val DAY_TUE = 2
    const val DAY_WED = 4
    const val DAY_THU = 8
    const val DAY_FRI = 16
    const val DAY_SAT = 32
    const val DAY_SUN = 64
    const val ALL_DAYS = DAY_MON or DAY_TUE or DAY_WED or DAY_THU or DAY_FRI or DAY_SAT or DAY_SUN

    /** Everything [evaluate] needs, gathered synchronously by the caller. */
    data class Facts(
        val nowMs: Long,
        val usageTodayMs: Long = 0,
        val usageThisHourMs: Long = 0,
        val launchesToday: Int = 0,
        val launchesThisHour: Int = 0,
        val goalUsageTodayMs: Long = 0,
        /** Distance to the rule's zone in metres, or null when no fix is available. */
        val distanceToZoneM: Float? = null,
        val zoneRadiusM: Int = 0,
        /**
         * Usage minutes recorded when the current wait was armed (see [armWaitTimer]).
         * Null when no wait was ever armed in this process: an expired wait then re-arms
         * immediately (fail-closed) instead of granting a fresh chunk.
         */
        val waitArmedUsageMin: Int? = null
    )

    fun evaluate(rule: BlockedApplication, facts: Facts): BlockReason? {
        val now = facts.nowMs
        // 1. Usage Limit
        if (rule.dailyLimitMinutes > 0 && facts.usageTodayMs >= rule.dailyLimitMinutes * 60_000L) {
            return BlockReason.DAILY_LIMIT
        }
        if (rule.hourlyLimitMinutes > 0 && facts.usageThisHourMs >= rule.hourlyLimitMinutes * 60_000L) {
            return BlockReason.HOURLY_LIMIT
        }
        // 2. Quick Block
        if (rule.quickBlockUntilEpochMs > now) return BlockReason.QUICK_BLOCK
        // 3. Number of Launches
        if (rule.maxLaunchesPerDay > 0 && facts.launchesToday >= rule.maxLaunchesPerDay) {
            return BlockReason.LAUNCH_LIMIT
        }
        if (rule.maxLaunchesPerHour > 0 && facts.launchesThisHour >= rule.maxLaunchesPerHour) {
            return BlockReason.LAUNCH_LIMIT
        }
        // 4. Wait Timer
        if (isWaitBlocking(rule, facts)) return BlockReason.WAIT_TIMER
        // 5. Goal Based
        if (rule.goalMinutes > 0 && rule.goalAppPackage != null &&
            facts.goalUsageTodayMs < rule.goalMinutes * 60_000L
        ) {
            return BlockReason.GOAL
        }
        // 6. Specific Time Intervals
        if (isIntervalActive(rule, now)) return BlockReason.TIME_INTERVAL
        // 7. Location
        if (isLocationBlocking(rule, facts)) return BlockReason.LOCATION
        return null
    }

    // ------------------------------------------------------------------ Wait Timer

    /**
     * True while a wait is running, or when a new wait is due. A wait is due when the app was used
     * for [BlockedApplication.waitAfterMinutes] (with no wait running) or when the previous wait
     * expired and another full chunk has been used since it was armed. The caller arms the wait
     * with [armWaitTimer] and persists the returned rule; until then the app stays blocked
     * (fail-closed: a wait that is never armed never ends).
     */
    fun isWaitBlocking(rule: BlockedApplication, facts: Facts): Boolean {
        if (rule.waitAfterMinutes <= 0 || rule.waitForMinutes <= 0) return false
        val now = facts.nowMs
        if (rule.waitUntilEpochMs > now) return true // a wait is running
        val usedMin = (facts.usageTodayMs / 60_000L).toInt()
        if (rule.waitUntilEpochMs == 0L) {
            return usedMin >= rule.waitAfterMinutes // first chunk used up: a wait is due
        }
        // A wait expired. The next chunk runs from the usage recorded when it was armed.
        val armedAt = facts.waitArmedUsageMin ?: 0
        return usedMin >= armedAt + rule.waitAfterMinutes
    }

    /**
     * Starts a wait now: returns the rule with [BlockedApplication.waitUntilEpochMs] set to
     * now + [BlockedApplication.waitForMinutes]. The caller must persist the returned rule
     * (BlockedAppDao.upsert) and remember [Facts.waitArmedUsageMin] = [usageTodayMin] for the
     * next evaluation, otherwise an expired wait re-arms immediately.
     */
    fun armWaitTimer(rule: BlockedApplication, usageTodayMin: Int, nowMs: Long): BlockedApplication {
        if (rule.waitForMinutes <= 0) return rule
        return rule.copy(waitUntilEpochMs = nowMs + rule.waitForMinutes * 60_000L)
    }

    // ------------------------------------------------------------------ Time intervals

    /** True when the rule has a time interval configured and [nowMs] falls inside it. */
    fun isIntervalActive(rule: BlockedApplication, nowMs: Long): Boolean {
        if (rule.fullDay) return isDaySelected(rule.intervalDays, nowMs)
        if (rule.intervalStartMin == rule.intervalEndMin) return false // no interval configured
        if (!isDaySelected(rule.intervalDays, nowMs)) return false
        val minuteOfDay = LocalTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            .let { it.hour * 60 + it.minute }
        return if (rule.intervalStartMin < rule.intervalEndMin) {
            minuteOfDay in rule.intervalStartMin until rule.intervalEndMin
        } else {
            // Overnight interval, e.g. 22:00-06:00.
            minuteOfDay >= rule.intervalStartMin || minuteOfDay < rule.intervalEndMin
        }
    }

    /** [intervalDays] == 0 means every day; otherwise the bit for today's weekday must be set. */
    fun isDaySelected(intervalDays: Int, nowMs: Long): Boolean {
        if (intervalDays == 0) return true
        val day = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).dayOfWeek
        val bit = 1 shl (day.value - 1) // MONDAY=1 -> bit 0
        return intervalDays and bit != 0
    }

    // ------------------------------------------------------------------ Location

    /**
     * True when the location gate fires. No fix ([Facts.distanceToZoneM] null) blocks only when
     * [BlockedApplication.blockIfLocationUnavailable] is set; otherwise the gate stays open so a
     * missing fix never blocks by accident.
     */
    fun isLocationBlocking(rule: BlockedApplication, facts: Facts): Boolean {
        if (rule.locationZoneId <= 0) return false
        val distance = facts.distanceToZoneM
            ?: return rule.blockIfLocationUnavailable
        val inside = distance <= facts.zoneRadiusM
        return if (rule.locationMode == "AWAY") !inside else inside
    }
}
