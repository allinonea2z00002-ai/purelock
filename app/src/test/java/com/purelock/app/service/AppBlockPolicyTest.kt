package com.purelock.app.service

import com.purelock.app.data.db.BlockedApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure policy evaluation tests. [ConditionEvaluator] is the pure core of [AppBlockPolicy]
 * (no Context, no database), so it is covered here with plain JUnit; AppBlockPolicy itself only
 * gathers the facts (UsageRepository, zones, fix) and delegates.
 */
class AppBlockPolicyTest {

    private val zone = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Monday 2026-11-16 10:00 (2026-11-20 is a Friday, so the 16th is a Monday). */
    private val monday10am = at(2026, 11, 16, 10)

    private fun rule(block: BlockedApplication.() -> BlockedApplication = { this }): BlockedApplication =
        BlockedApplication("com.example.app", "Example").let(block)

    private fun facts(
        nowMs: Long = monday10am,
        usageTodayMs: Long = 0,
        usageThisHourMs: Long = 0,
        launchesToday: Int = 0,
        launchesThisHour: Int = 0,
        goalUsageTodayMs: Long = 0,
        distanceToZoneM: Float? = null,
        zoneRadiusM: Int = 0,
        waitArmedUsageMin: Int? = null
    ) = ConditionEvaluator.Facts(
        nowMs, usageTodayMs, usageThisHourMs, launchesToday, launchesThisHour,
        goalUsageTodayMs, distanceToZoneM, zoneRadiusM, waitArmedUsageMin
    )

    // ------------------------------------------------------------------ usage limits

    @Test
    fun `daily limit blocks at the limit and not below`() {
        val r = rule { copy(dailyLimitMinutes = 30) }
        assertNull(ConditionEvaluator.evaluate(r, facts(usageTodayMs = 30 * 60_000L - 1)))
        assertEquals(BlockReason.DAILY_LIMIT, ConditionEvaluator.evaluate(r, facts(usageTodayMs = 30 * 60_000L)))
    }

    @Test
    fun `hourly limit blocks at the limit and not below`() {
        val r = rule { copy(hourlyLimitMinutes = 5) }
        assertNull(ConditionEvaluator.evaluate(r, facts(usageThisHourMs = 5 * 60_000L - 1)))
        assertEquals(BlockReason.HOURLY_LIMIT, ConditionEvaluator.evaluate(r, facts(usageThisHourMs = 5 * 60_000L)))
    }

    @Test
    fun `an old rule with conditionType NONE but a daily limit still blocks`() {
        val r = rule { copy(conditionType = "NONE", dailyLimitMinutes = 10) }
        assertEquals(BlockReason.DAILY_LIMIT, ConditionEvaluator.evaluate(r, facts(usageTodayMs = 10 * 60_000L)))
    }

    @Test
    fun `a rule with nothing configured never blocks`() {
        assertNull(ConditionEvaluator.evaluate(rule(), facts(usageTodayMs = 60_000L)))
    }

    // ------------------------------------------------------------------ time intervals

    @Test
    fun `interval blocks inside the window on a selected day`() {
        val r = rule {
            copy(
                intervalDays = ConditionEvaluator.DAY_MON or ConditionEvaluator.DAY_WED,
                intervalStartMin = 9 * 60, intervalEndMin = 17 * 60
            )
        }
        assertEquals(BlockReason.TIME_INTERVAL, ConditionEvaluator.evaluate(r, facts(nowMs = monday10am)))
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 16, 8, 59))))
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 16, 17, 0))))
    }

    @Test
    fun `interval does not fire on an unselected day`() {
        val r = rule {
            copy(
                intervalDays = ConditionEvaluator.DAY_TUE,
                intervalStartMin = 9 * 60, intervalEndMin = 17 * 60
            )
        }
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = monday10am)))
        assertEquals(
            BlockReason.TIME_INTERVAL,
            ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 17, 10)))
        )
    }

    @Test
    fun `interval with no days set applies every day`() {
        val r = rule { copy(intervalDays = 0, intervalStartMin = 9 * 60, intervalEndMin = 17 * 60) }
        assertEquals(BlockReason.TIME_INTERVAL, ConditionEvaluator.evaluate(r, facts(nowMs = monday10am)))
    }

    @Test
    fun `overnight interval wraps midnight`() {
        val r = rule { copy(intervalStartMin = 22 * 60, intervalEndMin = 6 * 60) }
        assertEquals(BlockReason.TIME_INTERVAL, ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 16, 23))))
        assertEquals(BlockReason.TIME_INTERVAL, ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 17, 5))))
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 16, 12))))
    }

    @Test
    fun `full day blocks the whole selected day and no other day`() {
        val r = rule { copy(fullDay = true, intervalDays = ConditionEvaluator.DAY_MON) }
        assertEquals(BlockReason.TIME_INTERVAL, ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 16, 3))))
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = at(2026, 11, 17, 3))))
    }

    @Test
    fun `start equals end with fullDay off means no interval`() {
        val r = rule { copy(intervalStartMin = 600, intervalEndMin = 600) }
        assertFalse(ConditionEvaluator.isIntervalActive(r, monday10am))
    }

    // ------------------------------------------------------------------ quick block

    @Test
    fun `quick block blocks until its end and not after`() {
        val r = rule { copy(quickBlockUntilEpochMs = monday10am + 10 * 60_000L) }
        assertEquals(BlockReason.QUICK_BLOCK, ConditionEvaluator.evaluate(r, facts(nowMs = monday10am)))
        assertNull(ConditionEvaluator.evaluate(r, facts(nowMs = monday10am + 10 * 60_000L)))
    }

    // ------------------------------------------------------------------ launches

    @Test
    fun `launch limit blocks at the daily and hourly caps`() {
        val r = rule { copy(maxLaunchesPerDay = 20, maxLaunchesPerHour = 2) }
        assertNull(ConditionEvaluator.evaluate(r, facts(launchesToday = 19, launchesThisHour = 1)))
        assertEquals(BlockReason.LAUNCH_LIMIT, ConditionEvaluator.evaluate(r, facts(launchesToday = 20)))
        assertEquals(BlockReason.LAUNCH_LIMIT, ConditionEvaluator.evaluate(r, facts(launchesToday = 5, launchesThisHour = 2)))
    }

    // ------------------------------------------------------------------ wait timer

    @Test
    fun `wait timer is not due before the chunk is used up`() {
        val r = rule { copy(waitAfterMinutes = 5, waitForMinutes = 30) }
        assertNull(ConditionEvaluator.evaluate(r, facts(usageTodayMs = 4 * 60_000L)))
        assertEquals(BlockReason.WAIT_TIMER, ConditionEvaluator.evaluate(r, facts(usageTodayMs = 5 * 60_000L)))
    }

    @Test
    fun `wait timer blocks while the wait is running`() {
        val r = rule {
            copy(waitAfterMinutes = 5, waitForMinutes = 30, waitUntilEpochMs = monday10am + 30 * 60_000L)
        }
        assertEquals(BlockReason.WAIT_TIMER, ConditionEvaluator.evaluate(r, facts(nowMs = monday10am)))
        assertNull(
            ConditionEvaluator.evaluate(
                r, facts(nowMs = monday10am + 31 * 60_000L, usageTodayMs = 5 * 60_000L, waitArmedUsageMin = 5)
            )
        )
    }

    @Test
    fun `after a served wait another full chunk is allowed before the next wait`() {
        val r = rule {
            copy(waitAfterMinutes = 5, waitForMinutes = 30, waitUntilEpochMs = monday10am - 1)
        }
        // Wait expired, armed at 5 used minutes: allowed until 10.
        assertNull(
            ConditionEvaluator.evaluate(
                r, facts(nowMs = monday10am, usageTodayMs = 9 * 60_000L, waitArmedUsageMin = 5)
            )
        )
        assertEquals(
            BlockReason.WAIT_TIMER,
            ConditionEvaluator.evaluate(
                r, facts(nowMs = monday10am, usageTodayMs = 10 * 60_000L, waitArmedUsageMin = 5)
            )
        )
    }

    @Test
    fun `an expired wait with no arming record re-arms immediately (fail-closed)`() {
        val r = rule {
            copy(waitAfterMinutes = 5, waitForMinutes = 30, waitUntilEpochMs = monday10am - 1)
        }
        assertEquals(
            BlockReason.WAIT_TIMER,
            ConditionEvaluator.evaluate(r, facts(nowMs = monday10am, usageTodayMs = 5 * 60_000L))
        )
    }

    @Test
    fun `armWaitTimer sets the wait end from now`() {
        val r = rule { copy(waitAfterMinutes = 5, waitForMinutes = 30) }
        val armed = ConditionEvaluator.armWaitTimer(r, usageTodayMin = 5, nowMs = monday10am)
        assertEquals(monday10am + 30 * 60_000L, armed.waitUntilEpochMs)
    }

    // ------------------------------------------------------------------ goal

    @Test
    fun `goal blocks until the goal minutes are spent on the goal app`() {
        val r = rule { copy(goalAppPackage = "com.example.study", goalMinutes = 30) }
        assertEquals(BlockReason.GOAL, ConditionEvaluator.evaluate(r, facts(goalUsageTodayMs = 29 * 60_000L)))
        assertNull(ConditionEvaluator.evaluate(r, facts(goalUsageTodayMs = 30 * 60_000L)))
    }

    @Test
    fun `goal without a goal app never blocks`() {
        val r = rule { copy(goalMinutes = 30) }
        assertNull(ConditionEvaluator.evaluate(r, facts()))
    }

    // ------------------------------------------------------------------ location

    @Test
    fun `location AT blocks inside the zone only`() {
        val r = rule { copy(locationZoneId = 7, locationMode = "AT") }
        assertEquals(
            BlockReason.LOCATION,
            ConditionEvaluator.evaluate(r, facts(distanceToZoneM = 50f, zoneRadiusM = 100))
        )
        assertNull(ConditionEvaluator.evaluate(r, facts(distanceToZoneM = 150f, zoneRadiusM = 100)))
    }

    @Test
    fun `location AWAY blocks outside the zone only`() {
        val r = rule { copy(locationZoneId = 7, locationMode = "AWAY") }
        assertNull(ConditionEvaluator.evaluate(r, facts(distanceToZoneM = 50f, zoneRadiusM = 100)))
        assertEquals(
            BlockReason.LOCATION,
            ConditionEvaluator.evaluate(r, facts(distanceToZoneM = 150f, zoneRadiusM = 100))
        )
    }

    @Test
    fun `location unavailable blocks only when blockIfLocationUnavailable is set`() {
        val strict = rule { copy(locationZoneId = 7, blockIfLocationUnavailable = true) }
        val lenient = rule { copy(locationZoneId = 7, blockIfLocationUnavailable = false) }
        assertEquals(BlockReason.LOCATION, ConditionEvaluator.evaluate(strict, facts(distanceToZoneM = null, zoneRadiusM = 100)))
        assertNull(ConditionEvaluator.evaluate(lenient, facts(distanceToZoneM = null, zoneRadiusM = 100)))
    }

    @Test
    fun `no zone means the location gate never fires`() {
        val r = rule { copy(locationZoneId = 0, blockIfLocationUnavailable = true) }
        assertNull(ConditionEvaluator.evaluate(r, facts(distanceToZoneM = null)))
    }
}
