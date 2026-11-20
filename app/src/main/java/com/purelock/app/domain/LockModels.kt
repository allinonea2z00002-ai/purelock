package com.purelock.app.domain

enum class LockMode {
    TIME, DISTANCE, COMBINED;

    val usesTime get() = this == TIME || this == COMBINED
    val usesDistance get() = this == DISTANCE || this == COMBINED

    companion object {
        fun fromName(name: String?): LockMode = values().firstOrNull { it.name == name } ?: TIME
    }
}

enum class CombineRule {
    /** Unlock when either condition is met. */
    ANY,
    /** Unlock only when both conditions are met. */
    ALL;

    companion object {
        fun fromName(name: String?): CombineRule = values().firstOrNull { it.name == name } ?: ANY
    }
}

/** What the UI shows for the current lock. Computed from stored state; never from fake values. */
data class LockSnapshot(
    val active: Boolean,
    val mode: LockMode,
    val rule: CombineRule,
    val durationMs: Long,
    val remainingMs: Long,
    val timeSatisfied: Boolean,
    val distanceSatisfied: Boolean,
    val distanceLimitM: Int,
    val distanceM: Float,
    /** Epoch of the last real location fix used for [distanceM]; 0 if none yet. */
    val lastLocationEpoch: Long,
    val blockAdult: Boolean,
    val blockApps: Boolean
) {
    /** 0f..1f progress toward unlocking, for the countdown ring. */
    val progress: Float
        get() {
            val timeProgress =
                if (durationMs > 0) (1f - remainingMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val distanceProgress =
                if (distanceLimitM > 0) (distanceM / distanceLimitM.toFloat()).coerceIn(0f, 1f) else 0f
            val t = if (timeSatisfied) 1f else timeProgress
            val d = if (distanceSatisfied) 1f else distanceProgress
            return when (mode) {
                LockMode.TIME -> t
                LockMode.DISTANCE -> d
                LockMode.COMBINED -> if (rule == CombineRule.ANY) maxOf(t, d) else minOf(t, d)
            }
        }

    companion object {
        val INACTIVE = LockSnapshot(
            active = false, mode = LockMode.TIME, rule = CombineRule.ANY,
            durationMs = 0, remainingMs = 0, timeSatisfied = false, distanceSatisfied = false,
            distanceLimitM = 0, distanceM = 0f, lastLocationEpoch = 0,
            blockAdult = false, blockApps = false
        )
    }
}
