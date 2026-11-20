package com.purelock.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per started lock. Closed (endEpoch/endReason set) when the lock finishes. */
@Entity(tableName = "protection_session")
data class ProtectionSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val startEpoch: Long,
    val plannedDurationMs: Long,
    val distanceLimitM: Int,
    val combineRule: String,
    val endEpoch: Long? = null,
    /** COMPLETED, EMERGENCY_OVERRIDE or TEST_ENDED */
    val endReason: String? = null,
    /** True for a 60-second test-mode trial lock (never counted in real statistics). */
    val isTest: Boolean = false
)

/**
 * The single active/last lock configuration (id is always 1).
 *
 * Time is tracked with a checkpoint: [remainingAtCheckpointMs] was left at the moment recorded by
 * [checkpointElapsed] (monotonic clock, survives clock changes) and [checkpointEpoch] (wall clock,
 * used only after a reboot when the monotonic clock restarted). [bootCount] tells which case applies.
 */
@Entity(tableName = "lock_configuration")
data class LockConfiguration(
    @PrimaryKey val id: Int = 1,
    val active: Boolean = false,
    /** True while a 60-second test-mode trial lock is running (never a real lock). */
    val isTest: Boolean = false,
    val mode: String = "TIME",
    val combineRule: String = "ANY",
    val durationMs: Long = 0,
    val distanceLimitM: Int = 0,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val startEpoch: Long = 0,
    val remainingAtCheckpointMs: Long = 0,
    val checkpointElapsed: Long = 0,
    val checkpointEpoch: Long = 0,
    val bootCount: Int = -1,
    val sessionId: Long = 0,
    val timeSatisfied: Boolean = false,
    val distanceSatisfied: Boolean = false,
    val blockAdult: Boolean = true,
    val blockApps: Boolean = true,
    val filterWasEnabled: Boolean = false,
    val lastDistanceM: Float = 0f,
    val lastLocationEpoch: Long = 0,
    val lastLocationAccuracyM: Float = 0f
)

@Entity(tableName = "blocked_application")
data class BlockedApplication(
    @PrimaryKey val packageName: String,
    val label: String,
    /** Block this app while a lock is active. */
    val blockDuringLock: Boolean = true,
    /** 0 = no usage limit. Otherwise the app is blocked once today's usage reaches this many minutes. */
    val dailyLimitMinutes: Int = 0,
    // ---- Blocking conditions (all optional; defaults mean "not configured") ----
    /**
     * The condition picked in the chooser: NONE, USAGE_LIMIT, TIME_INTERVAL, QUICK_BLOCK,
     * LAUNCH_LIMIT, WAIT_TIMER, GOAL or LOCATION. Display state only: enforcement keys off the
     * configured values below, so an old rule (NONE with a daily limit) keeps working.
     */
    val conditionType: String = "NONE",
    /** 0 = no hourly limit. Otherwise blocked once this hour's usage reaches this many minutes. */
    val hourlyLimitMinutes: Int = 0,
    /**
     * Days the time-interval condition applies to, as a bitmask: Mon=1, Tue=2, Wed=4, Thu=8,
     * Fri=16, Sat=32, Sun=64. 0 = every day.
     */
    val intervalDays: Int = 0,
    /** Start of the blocked interval, minutes since midnight. Ignored when [fullDay] is true. */
    val intervalStartMin: Int = 0,
    /** End of the blocked interval, minutes since midnight. Start == end (and not [fullDay]) = no interval. */
    val intervalEndMin: Int = 0,
    /** True = blocked the whole day (on the selected days) instead of a time range. */
    val fullDay: Boolean = false,
    /** Quick Block: blocked until this wall-clock epoch. 0 = no quick block. */
    val quickBlockUntilEpochMs: Long = 0,
    /** 0 = no limit. Otherwise blocked after this many launches today. */
    val maxLaunchesPerDay: Int = 0,
    /** 0 = no limit. Otherwise blocked after this many launches this hour. */
    val maxLaunchesPerHour: Int = 0,
    /** Wait Timer: use the app this many minutes, then wait. 0 = no wait timer. */
    val waitAfterMinutes: Int = 0,
    /** Wait Timer: wait this many minutes after each [waitAfterMinutes] chunk of use. */
    val waitForMinutes: Int = 0,
    /** Wait Timer state: the current wait ends at this wall-clock epoch. 0 = no wait running. */
    val waitUntilEpochMs: Long = 0,
    /** Goal Based: block until this many minutes were spent on [goalAppPackage] today. Null = no goal. */
    val goalAppPackage: String? = null,
    /** Goal Based: minutes to spend on the goal app before this app unblocks. 0 = no goal. */
    val goalMinutes: Int = 0,
    /** Location: the SafeZone this rule is tied to. 0 = no location condition. */
    val locationZoneId: Long = 0,
    /** Location: "AT" = block while inside the zone, "AWAY" = block while outside it. */
    val locationMode: String = "AT",
    /** Location: when true and no fix is available, the app stays blocked instead of opening. */
    val blockIfLocationUnavailable: Boolean = false,
    /** "I Want to Block": block the app launch itself. */
    val blockLaunch: Boolean = true,
    /**
     * "I Want to Block": block the app's notifications. Stored only — PureLock has no notification
     * listener, so this is not enforced yet and the UI says so.
     */
    val blockNotifications: Boolean = false
)

@Entity(tableName = "allowed_application")
data class AllowedApplication(
    @PrimaryKey val packageName: String,
    val label: String
)

@Entity(tableName = "blocked_domain")
data class BlockedDomain(
    @PrimaryKey val domain: String,
    val addedEpoch: Long
)

@Entity(tableName = "allowed_domain")
data class AllowedDomain(
    @PrimaryKey val domain: String,
    val addedEpoch: Long
)

@Entity(tableName = "location_record")
data class LocationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val epoch: Long,
    /** START, APP, WORKER, GEOFENCE */
    val source: String
)

/** Aggregated per local calendar day (dateKey = yyyy-MM-dd). Only real, recorded events are counted. */
@Entity(tableName = "focus_statistics")
data class FocusStatistics(
    @PrimaryKey val dateKey: String,
    val protectedMs: Long = 0,
    val websiteBlocks: Int = 0,
    val appBlocks: Int = 0,
    val interruptions: Int = 0,
    val emergencyOverrides: Int = 0
)

@Entity(tableName = "security_event")
data class SecurityEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epoch: Long,
    val type: String,
    val detail: String = ""
)

/** Small key/value table for app metadata (filter-list update info, last integrity issues, ...). */
@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val key: String,
    val value: String
)

/** A place that automatically starts a time lock when the user enters it (location-based rule). */
@Entity(tableName = "safe_zone")
data class SafeZone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    /** 0 = just a named place. >0 = start a time lock of this many minutes on entering. */
    val autoLockMinutes: Int = 0
)

object SecurityEventType {
    const val LOCK_STARTED = "LOCK_STARTED"
    const val LOCK_COMPLETED = "LOCK_COMPLETED"
    const val EMERGENCY_OVERRIDE = "EMERGENCY_OVERRIDE"
    const val TIME_CHANGED = "TIME_CHANGED"
    const val REBOOT_RECOVERY = "REBOOT_RECOVERY"
    const val INTEGRITY_ISSUE = "INTEGRITY_ISSUE"
    const val INTEGRITY_RESTORED = "INTEGRITY_RESTORED"
    const val GEOFENCE_ERROR = "GEOFENCE_ERROR"
    const val ZONE_LOCK = "ZONE_LOCK"
    const val DATA_CLEARED = "DATA_CLEARED"
    /** A test-mode trial lock was ended early from the Home test banner. */
    const val TEST_ENDED = "TEST_ENDED"
}
