package com.purelock.app.domain

import com.purelock.app.data.db.LockConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockEvaluatorTest {

    private val hour = 3_600_000L

    // ---------------------------------------------------------------- time keeping

    @Test
    fun `same boot uses the monotonic clock`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 1_000,
            checkpointEpoch = 5_000_000,
            nowElapsed = 601_000,
            nowEpoch = 9_999_999_999,
            sameBoot = true
        )
        assertEquals(hour - 600_000, remaining)
    }

    @Test
    fun `moving the wall clock backwards does not add time`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 0,
            checkpointEpoch = 1_000_000_000,
            nowElapsed = 10_000,
            nowEpoch = 1_000_000_000 - 86_400_000, // user set the date back one day
            sameBoot = true
        )
        assertEquals(hour - 10_000, remaining)
    }

    @Test
    fun `moving the wall clock forwards does not shorten the lock`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 0,
            checkpointEpoch = 1_000_000_000,
            nowElapsed = 10_000,
            nowEpoch = 1_000_000_000 + 30L * 86_400_000, // user set the date a month ahead
            sameBoot = true
        )
        assertEquals(hour - 10_000, remaining)
    }

    @Test
    fun `after a reboot the wall clock is used for the time the device was off`() {
        val remaining = LockEvaluator.remainingMs(
            remainingAtCheckpointMs = hour,
            checkpointElapsed = 999_999_999, // from before the reboot, meaningless now
            checkpointEpoch = 1_000_000,
            nowElapsed = 5_000,
            nowEpoch = 1_000_000 + 120_000,
            sameBoot = false
        )
        assertEquals(hour - 120_000, remaining)
    }

    @Test
    fun `after a reboot a wall clock set backwards never adds time`() {
        val remaining = LockEvaluator.remainingMs(hour, 0, 1_000_000, 5_000, 1_000_000 - 500_000, sameBoot = false)
        assertEquals(hour, remaining)
    }

    @Test
    fun `remaining time never goes below zero`() {
        val remaining = LockEvaluator.remainingMs(1_000, 0, 0, 50_000, 50_000, sameBoot = true)
        assertEquals(0L, remaining)
    }

    @Test
    fun `boot detection`() {
        assertTrue(LockEvaluator.isSameBoot(5, 5, 100, 500))
        assertFalse(LockEvaluator.isSameBoot(5, 6, 100, 500))
        // boot counter unavailable: fall back to "the monotonic clock did not go backwards"
        assertFalse(LockEvaluator.isSameBoot(-1, -1, 100_000, 500))
        assertTrue(LockEvaluator.isSameBoot(-1, -1, 100, 500))
    }

    // ---------------------------------------------------------------- unlock rules

    @Test
    fun `time mode only looks at time`() {
        assertTrue(LockEvaluator.isUnlocked(LockMode.TIME, CombineRule.ALL, timeSatisfied = true, distanceSatisfied = false))
        assertFalse(LockEvaluator.isUnlocked(LockMode.TIME, CombineRule.ANY, timeSatisfied = false, distanceSatisfied = true))
    }

    @Test
    fun `distance mode only looks at distance`() {
        assertTrue(LockEvaluator.isUnlocked(LockMode.DISTANCE, CombineRule.ANY, timeSatisfied = false, distanceSatisfied = true))
        assertFalse(LockEvaluator.isUnlocked(LockMode.DISTANCE, CombineRule.ANY, timeSatisfied = true, distanceSatisfied = false))
    }

    @Test
    fun `combined ANY unlocks when either is satisfied`() {
        val any = CombineRule.ANY
        assertFalse(LockEvaluator.isUnlocked(LockMode.COMBINED, any, false, false))
        assertTrue(LockEvaluator.isUnlocked(LockMode.COMBINED, any, true, false))
        assertTrue(LockEvaluator.isUnlocked(LockMode.COMBINED, any, false, true))
        assertTrue(LockEvaluator.isUnlocked(LockMode.COMBINED, any, true, true))
    }

    @Test
    fun `combined ALL unlocks only when both are satisfied`() {
        val all = CombineRule.ALL
        assertFalse(LockEvaluator.isUnlocked(LockMode.COMBINED, all, false, false))
        assertFalse(LockEvaluator.isUnlocked(LockMode.COMBINED, all, true, false))
        assertFalse(LockEvaluator.isUnlocked(LockMode.COMBINED, all, false, true))
        assertTrue(LockEvaluator.isUnlocked(LockMode.COMBINED, all, true, true))
    }

    // ---------------------------------------------------------------- distance

    @Test
    fun `distance goal needs the limit and a usable accuracy`() {
        assertTrue(LockEvaluator.distanceReached(1_000f, 10f, 1_000))
        assertTrue(LockEvaluator.distanceReached(2_500f, 150f, 1_000))
        assertFalse(LockEvaluator.distanceReached(999.9f, 10f, 1_000))
        assertFalse(LockEvaluator.distanceReached(5_000f, 300f, 1_000)) // too inaccurate to trust
        assertFalse(LockEvaluator.distanceReached(5_000f, 10f, 0))      // no limit configured
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun `custom duration limits`() {
        assertFalse(LockEvaluator.isValidCustomMinutes(2))
        assertTrue(LockEvaluator.isValidCustomMinutes(3))
        assertTrue(LockEvaluator.isValidCustomMinutes(5))
        assertTrue(LockEvaluator.isValidCustomMinutes(90L * 24 * 60))
        assertFalse(LockEvaluator.isValidCustomMinutes(90L * 24 * 60 + 1))
    }

    @Test
    fun `custom distance limits`() {
        assertFalse(LockEvaluator.isValidDistance(499))
        assertTrue(LockEvaluator.isValidDistance(500))
        assertTrue(LockEvaluator.isValidDistance(200_000))
        assertFalse(LockEvaluator.isValidDistance(200_001))
    }

    @Test
    fun `presets match the specification`() {
        assertEquals(10, LockEvaluator.TIME_PRESETS_MS.size)
        assertEquals(30L * 60_000, LockEvaluator.TIME_PRESETS_MS.first())
        assertEquals(30L * 86_400_000, LockEvaluator.TIME_PRESETS_MS.last())
        assertEquals(listOf(1_000, 3_000, 5_000, 10_000, 20_000), LockEvaluator.DISTANCE_PRESETS_M)
    }

    // ---------------------------------------------------------------- snapshot

    private fun timeConfig(remaining: Long = hour, bootCount: Int = 1) = LockConfiguration(
        active = true,
        mode = "TIME",
        durationMs = hour,
        remainingAtCheckpointMs = remaining,
        checkpointElapsed = 0,
        checkpointEpoch = 0,
        bootCount = bootCount
    )

    @Test
    fun `no configuration means inactive`() {
        assertFalse(LockEvaluator.snapshot(null, 0, 0, 1).active)
        assertFalse(LockEvaluator.snapshot(timeConfig().copy(active = false), 0, 0, 1).active)
    }

    @Test
    fun `snapshot reports remaining time and progress`() {
        val snap = LockEvaluator.snapshot(timeConfig(), nowElapsed = hour / 2, nowEpoch = hour / 2, currentBootCount = 1)
        assertTrue(snap.active)
        assertEquals(hour / 2, snap.remainingMs)
        assertFalse(snap.timeSatisfied)
        assertEquals(0.5f, snap.progress, 0.001f)
    }

    @Test
    fun `snapshot marks time satisfied when the countdown reaches zero`() {
        val snap = LockEvaluator.snapshot(timeConfig(remaining = 1_000), nowElapsed = 5_000, nowEpoch = 5_000, currentBootCount = 1)
        assertEquals(0L, snap.remainingMs)
        assertTrue(snap.timeSatisfied)
    }

    @Test
    fun `combined progress follows the unlock rule`() {
        fun snap(rule: CombineRule) = LockSnapshot(
            active = true, mode = LockMode.COMBINED, rule = rule,
            durationMs = 1_000, remainingMs = 500,
            timeSatisfied = false, distanceSatisfied = false,
            distanceLimitM = 1_000, distanceM = 250f, lastLocationEpoch = 0,
            blockAdult = true, blockApps = true
        )
        assertEquals(0.5f, snap(CombineRule.ANY).progress, 0.001f)  // furthest along
        assertEquals(0.25f, snap(CombineRule.ALL).progress, 0.001f) // slowest one
    }
}
