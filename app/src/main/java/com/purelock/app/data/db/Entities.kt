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
    /** COMPLETED or EMERGENCY_OVERRIDE */
    val endReason: String? = null
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
    val dailyLimitMinutes: Int = 0
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
    const val VPN_REVOKED = "VPN_REVOKED"
    const val INTEGRITY_ISSUE = "INTEGRITY_ISSUE"
    const val INTEGRITY_RESTORED = "INTEGRITY_RESTORED"
    const val GEOFENCE_ERROR = "GEOFENCE_ERROR"
    const val ZONE_LOCK = "ZONE_LOCK"
    const val DATA_CLEARED = "DATA_CLEARED"
}
