package com.purelock.app.domain

import com.purelock.app.data.db.LockConfiguration

/** Pure lock rules. No Android dependencies, so they are covered by plain unit tests. */
object LockEvaluator {

    const val MIN_CUSTOM_MINUTES = 5L
    const val MAX_CUSTOM_MINUTES = 90L * 24 * 60
    const val MIN_DISTANCE_M = 500
    const val MAX_DISTANCE_M = 200_000

    /** Fixes worse than this are ignored when deciding that the distance goal was reached. */
    const val MAX_ACCEPTED_ACCURACY_M = 200f

    val TIME_PRESETS_MS: List<Long> = listOf(
        30L * MIN, 1L * HOUR, 3L * HOUR, 6L * HOUR, 12L * HOUR, 24L * HOUR,
        3L * DAY, 7L * DAY, 15L * DAY, 30L * DAY
    )
    val DISTANCE_PRESETS_M: List<Int> = listOf(1_000, 3_000, 5_000, 10_000, 20_000)

    /**
     * Time left, given a checkpoint.
     *
     * Same boot: use the monotonic clock (elapsedRealtime), which keeps counting in deep sleep and
     * cannot be changed by the user, so editing the system clock does not shorten the lock.
     * After a reboot the monotonic clock restarted, so the wall clock is used for the time the
     * device was off. A clock moved backwards never *adds* time and never goes negative.
     */
    fun remainingMs(
        remainingAtCheckpointMs: Long,
        checkpointElapsed: Long,
        checkpointEpoch: Long,
        nowElapsed: Long,
        nowEpoch: Long,
        sameBoot: Boolean
    ): Long {
        val passed = passedMs(checkpointElapsed, checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        return (remainingAtCheckpointMs - passed).coerceAtLeast(0L)
    }

    fun passedMs(
        checkpointElapsed: Long,
        checkpointEpoch: Long,
        nowElapsed: Long,
        nowEpoch: Long,
        sameBoot: Boolean
    ): Long {
        val raw = if (sameBoot) nowElapsed - checkpointElapsed else nowEpoch - checkpointEpoch
        return raw.coerceAtLeast(0L)
    }

    /** True when the stored boot counter says "same boot"; falls back to the monotonic clock when unknown. */
    fun isSameBoot(storedBootCount: Int, currentBootCount: Int, checkpointElapsed: Long, nowElapsed: Long): Boolean =
        if (storedBootCount >= 0 && currentBootCount >= 0) storedBootCount == currentBootCount
        else nowElapsed >= checkpointElapsed

    fun isUnlocked(mode: LockMode, rule: CombineRule, timeSatisfied: Boolean, distanceSatisfied: Boolean): Boolean =
        when (mode) {
            LockMode.TIME -> timeSatisfied
            LockMode.DISTANCE -> distanceSatisfied
            LockMode.COMBINED -> if (rule == CombineRule.ANY) timeSatisfied || distanceSatisfied
            else timeSatisfied && distanceSatisfied
        }

    /** The goal is "travel at least [limitM] away from the starting point". */
    fun distanceReached(distanceM: Float, accuracyM: Float, limitM: Int): Boolean =
        limitM > 0 && distanceM >= limitM && accuracyM <= MAX_ACCEPTED_ACCURACY_M

    fun isValidCustomMinutes(minutes: Long) = minutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES
    fun isValidDistance(meters: Int) = meters in MIN_DISTANCE_M..MAX_DISTANCE_M

    /** Read-only view of a stored configuration, used by the UI ticker (no database writes). */
    fun snapshot(
        cfg: LockConfiguration?,
        nowElapsed: Long,
        nowEpoch: Long,
        currentBootCount: Int
    ): LockSnapshot {
        if (cfg == null || !cfg.active) return LockSnapshot.INACTIVE
        val mode = LockMode.fromName(cfg.mode)
        val sameBoot = isSameBoot(cfg.bootCount, currentBootCount, cfg.checkpointElapsed, nowElapsed)
        val remaining = if (mode.usesTime) {
            remainingMs(cfg.remainingAtCheckpointMs, cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        } else 0L
        return LockSnapshot(
            active = true,
            mode = mode,
            rule = CombineRule.fromName(cfg.combineRule),
            durationMs = cfg.durationMs,
            remainingMs = remaining,
            timeSatisfied = cfg.timeSatisfied || (mode.usesTime && remaining == 0L),
            distanceSatisfied = cfg.distanceSatisfied,
            distanceLimitM = cfg.distanceLimitM,
            distanceM = cfg.lastDistanceM,
            lastLocationEpoch = cfg.lastLocationEpoch,
            blockAdult = cfg.blockAdult,
            blockApps = cfg.blockApps
        )
    }

    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
}
