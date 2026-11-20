package com.purelock.app.domain

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import android.provider.Settings
import com.purelock.app.R
import com.purelock.app.admin.DevicePolicyHelper
import com.purelock.app.data.EventLog
import com.purelock.app.data.StatsRepository
import com.purelock.app.data.db.AppDatabase
import com.purelock.app.data.db.LocationRecord
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.db.ProtectionSession
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.data.prefs.SettingsStore
import com.purelock.app.location.GeofenceManager
import com.purelock.app.location.LocationHelper
import com.purelock.app.service.WorkScheduler
import com.purelock.app.util.Notifier
import com.purelock.app.vpn.PureLockVpnService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Smart Lock engine. All state lives in the database (see [LockConfiguration]); nothing is held
 * only in memory, so the lock survives app closure, process death, screen-off and reboot.
 */
class LockManager(
    private val context: Context,
    private val db: AppDatabase,
    private val stats: StatsRepository,
    private val log: EventLog,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val geofences: GeofenceManager
) {
    private val mutex = Mutex()
    private val dao get() = db.lockDao()

    companion object {
        /** Length of a test-mode trial lock: 60 seconds, always. */
        const val TEST_DURATION_MS = 60_000L
    }

    data class StartRequest(
        val mode: LockMode,
        val durationMs: Long,
        val distanceM: Int,
        val rule: CombineRule,
        val startLat: Double?,
        val startLng: Double?,
        val blockAdult: Boolean,
        val blockApps: Boolean,
        /** True for a test-mode trial lock. Bypasses the 5-minute minimum; never counts in statistics. */
        val isTest: Boolean = false
    )

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyActive : StartResult
        data object InvalidDuration : StartResult
        data object InvalidDistance : StartResult
        data object NeedsStartLocation : StartResult
    }

    fun observe(): Flow<LockConfiguration?> = dao.observe()

    suspend fun current(): LockConfiguration? = dao.get()

    suspend fun isActive(): Boolean = dao.get()?.active == true

    /** True while a test-mode trial lock is running. The engine treats it as a real lock for
     * blocking; only statistics and notifications tell it apart. */
    suspend fun isTestLock(): Boolean = dao.get()?.let { it.active && it.isTest } ?: false

    /**
     * Starts a 60-second test-mode trial lock through the real lock engine: same checkpoint timer,
     * same boot recovery, same WorkManager end-job, same blocking. The 5-minute minimum does not
     * apply to a test. Returns [StartResult.AlreadyActive] when any lock (real or test) is running.
     * The adult filter runs only when VPN consent was already granted (same rule as zone locks).
     */
    suspend fun startTest(): StartResult {
        val canFilter = VpnService.prepare(context) == null
        return start(
            StartRequest(
                mode = LockMode.TIME,
                durationMs = TEST_DURATION_MS,
                distanceM = 0,
                rule = CombineRule.ANY,
                startLat = null,
                startLng = null,
                blockAdult = canFilter,
                blockApps = true,
                isTest = true
            )
        )
    }

    /**
     * Ends a test-mode trial lock at once. This is the ONLY way to end a lock early without the
     * emergency override, and it works only for test locks: a real lock is never touched here.
     * Nothing is credited to statistics.
     */
    suspend fun endTest(): Boolean = mutex.withLock {
        val cfg = dao.get() ?: return@withLock false
        if (!cfg.active || !cfg.isTest) return@withLock false
        log.log(SecurityEventType.TEST_ENDED, "")
        finishLocked(cfg, "TEST_ENDED")
        true
    }

    fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    // ---------------------------------------------------------------- start

    suspend fun start(req: StartRequest): StartResult = mutex.withLock {
        val existing = dao.get()
        if (existing?.active == true) return@withLock StartResult.AlreadyActive

        if (req.mode.usesTime && !req.isTest) {
            val minutes = req.durationMs / 60_000
            val isPreset = LockEvaluator.TIME_PRESETS_MS.contains(req.durationMs)
            if (!isPreset && !LockEvaluator.isValidCustomMinutes(minutes)) return@withLock StartResult.InvalidDuration
        }
        if (req.mode.usesDistance) {
            if (!LockEvaluator.isValidDistance(req.distanceM)) return@withLock StartResult.InvalidDistance
            if (req.startLat == null || req.startLng == null) return@withLock StartResult.NeedsStartLocation
        }

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val currentSettings = settings.get()

        val sessionId = db.sessionDao().insert(
            ProtectionSession(
                mode = req.mode.name,
                startEpoch = now,
                plannedDurationMs = if (req.mode.usesTime) req.durationMs else 0,
                distanceLimitM = if (req.mode.usesDistance) req.distanceM else 0,
                combineRule = req.rule.name,
                isTest = req.isTest
            )
        )

        val config = LockConfiguration(
            active = true,
            isTest = req.isTest,
            mode = req.mode.name,
            combineRule = req.rule.name,
            durationMs = if (req.mode.usesTime) req.durationMs else 0,
            distanceLimitM = if (req.mode.usesDistance) req.distanceM else 0,
            startLat = req.startLat,
            startLng = req.startLng,
            startEpoch = now,
            remainingAtCheckpointMs = if (req.mode.usesTime) req.durationMs else 0,
            checkpointElapsed = elapsed,
            checkpointEpoch = now,
            bootCount = bootCount(),
            sessionId = sessionId,
            blockAdult = req.blockAdult,
            blockApps = req.blockApps,
            filterWasEnabled = currentSettings.filterEnabled
        )
        dao.upsert(config)

        if (req.mode.usesDistance && req.startLat != null && req.startLng != null) {
            db.locationDao().insert(
                LocationRecord(latitude = req.startLat, longitude = req.startLng, accuracyM = 0f, epoch = now, source = "START")
            )
        }

        if (req.blockAdult) settings.set(SettingsKeys.FILTER_ENABLED, true)
        log.log(SecurityEventType.LOCK_STARTED, if (req.isTest) "TEST" else "${req.mode.name}/${req.rule.name}")

        geofences.sync(config, db.safeZoneDao().getAll())
        if (req.mode.usesTime) WorkScheduler.scheduleUnlock(context, req.durationMs)
        WorkScheduler.schedulePeriodic(context)
        if (req.blockAdult) PureLockVpnService.start(context, req.isTest)
        DevicePolicyHelper.applyLockRestrictions(context)
        StartResult.Started
    }

    // ----------------------------------------------------------- evaluation

    /**
     * Advances the checkpoint, credits protected time, latches time conditions and finishes the lock
     * when its unlock rule is satisfied. Safe to call at any moment and any number of times.
     */
    suspend fun checkpoint(): LockConfiguration? = mutex.withLock { checkpointLocked() }

    private suspend fun checkpointLocked(): LockConfiguration? {
        val cfg = dao.get() ?: return null
        if (!cfg.active) return cfg

        val mode = LockMode.fromName(cfg.mode)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)

        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val remaining =
            if (mode.usesTime) (cfg.remainingAtCheckpointMs - passed).coerceAtLeast(0) else 0L
        // Never credit more protected time than the lock actually had left.
        // A test lock credits nothing: trial minutes must not pollute real statistics.
        val credited = if (mode.usesTime) minOf(passed, cfg.remainingAtCheckpointMs) else passed
        if (!cfg.isTest) stats.addProtected(nowEpoch - credited, credited)

        val updated = cfg.copy(
            remainingAtCheckpointMs = remaining,
            checkpointElapsed = nowElapsed,
            checkpointEpoch = nowEpoch,
            bootCount = bootCount(),
            timeSatisfied = cfg.timeSatisfied || (mode.usesTime && remaining == 0L)
        )
        return evaluateAndSave(updated)
    }

    private suspend fun evaluateAndSave(cfg: LockConfiguration): LockConfiguration {
        val unlocked = LockEvaluator.isUnlocked(
            LockMode.fromName(cfg.mode), CombineRule.fromName(cfg.combineRule),
            cfg.timeSatisfied, cfg.distanceSatisfied
        )
        if (unlocked) {
            return finishLocked(cfg, "COMPLETED")
        }
        dao.upsert(cfg)
        return cfg
    }

    // ------------------------------------------------------------- distance

    /** Feeds a real location fix into an active Distance/Combined lock. */
    suspend fun onLocation(lat: Double, lng: Double, accuracyM: Float, source: String): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active || !LockMode.fromName(cfg.mode).usesDistance) return@withLock
        val startLat = cfg.startLat ?: return@withLock
        val startLng = cfg.startLng ?: return@withLock

        val now = System.currentTimeMillis()
        val distance = LocationHelper.distanceMeters(startLat, startLng, lat, lng)
        val reached = LockEvaluator.distanceReached(distance, accuracyM, cfg.distanceLimitM)

        if (settings.get().storeLocationHistory) {
            val last = db.locationDao().latest()
            if (source != "APP" || last == null || now - last.epoch >= 5 * 60_000L) {
                db.locationDao().insert(LocationRecord(latitude = lat, longitude = lng, accuracyM = accuracyM, epoch = now, source = source))
            }
            db.locationDao().deleteOlderThan(now - 30L * 86_400_000L)
        }

        val updated = cfg.copy(
            lastDistanceM = distance,
            lastLocationEpoch = now,
            lastLocationAccuracyM = accuracyM,
            distanceSatisfied = cfg.distanceSatisfied || reached
        )
        if (reached && !cfg.distanceSatisfied) {
            // Update the time part too so the unlock decision uses fresh numbers.
            evaluateAndSave(updated)
        } else {
            dao.upsert(updated)
        }
    }

    /** Called by the geofence EXIT transition around the starting point. */
    suspend fun onGeofenceExit(): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active || !LockMode.fromName(cfg.mode).usesDistance || cfg.distanceSatisfied) return@withLock
        val limit = cfg.distanceLimitM.toFloat()
        evaluateAndSave(
            cfg.copy(
                distanceSatisfied = true,
                lastDistanceM = maxOf(cfg.lastDistanceM, limit),
                lastLocationEpoch = System.currentTimeMillis()
            )
        )
    }

    // ----------------------------------------------------------- zone locks

    /** A safe zone with an automatic lock was entered: start a time lock if none is active. */
    suspend fun startZoneLock(zoneId: Long) {
        val zone = db.safeZoneDao().get(zoneId) ?: return
        if (zone.autoLockMinutes <= 0 || isActive()) return
        // From the background we cannot ask for VPN consent; only use the filter if consent already exists.
        val canFilter = VpnService.prepare(context) == null
        val result = start(
            StartRequest(
                mode = LockMode.TIME,
                durationMs = zone.autoLockMinutes * 60_000L,
                distanceM = 0,
                rule = CombineRule.ANY,
                startLat = null,
                startLng = null,
                blockAdult = canFilter,
                blockApps = true
            )
        )
        if (result is StartResult.Started) {
            log.log(SecurityEventType.ZONE_LOCK, zone.name)
            notifier.event(
                Notifier.ID_ZONE,
                context.getString(R.string.notif_zone_lock_title),
                context.getString(R.string.notif_zone_lock_text, zone.name, zone.autoLockMinutes)
            )
        }
    }

    // ---------------------------------------------------------------- finish

    /**
     * Ends the lock at once. Always available and always logged: a lock must never trap someone in a
     * genuine emergency. The UI asks for an explicit confirmation first.
     */
    suspend fun emergencyOverride(): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active) return@withLock
        // Credit the time that really passed before ending.
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)
        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val credited = if (LockMode.fromName(cfg.mode).usesTime) minOf(passed, cfg.remainingAtCheckpointMs) else passed
        // A test lock credits no protected time and counts no emergency override.
        if (!cfg.isTest) {
            stats.addProtected(nowEpoch - credited, credited)
            stats.recordEmergencyOverride(nowEpoch)
        }
        log.log(SecurityEventType.EMERGENCY_OVERRIDE, if (cfg.isTest) "TEST" else "")
        finishLocked(cfg, "EMERGENCY_OVERRIDE")
    }

    private suspend fun finishLocked(cfg: LockConfiguration, reason: String): LockConfiguration {
        val now = System.currentTimeMillis()
        db.sessionDao().get(cfg.sessionId)?.let {
            db.sessionDao().update(it.copy(endEpoch = now, endReason = reason))
        }
        val finished = cfg.copy(active = false)
        dao.upsert(finished)

        geofences.sync(null, db.safeZoneDao().getAll())
        WorkScheduler.cancelUnlock(context)
        DevicePolicyHelper.releaseLockRestrictions(context)

        val keep = settings.get().keepFilterAfterLock || cfg.filterWasEnabled
        if (!keep) {
            settings.set(SettingsKeys.FILTER_ENABLED, false)
            PureLockVpnService.stop(context)
        } else if (cfg.isTest) {
            // The filter stays on after the trial: drop the TEST label from its notification.
            PureLockVpnService.start(context, false)
        }

        if (reason == "COMPLETED") {
            log.log(SecurityEventType.LOCK_COMPLETED, if (cfg.isTest) "TEST" else cfg.mode)
            notifier.event(
                Notifier.ID_UNLOCKED,
                context.getString(if (cfg.isTest) R.string.notif_test_completed_title else R.string.notif_completed_title),
                context.getString(if (cfg.isTest) R.string.notif_test_completed_text else R.string.notif_completed_text)
            )
        }
        return finished
    }

    // ------------------------------------------------------ system recovery

    /** Wall-clock or time-zone change. The monotonic clock is unaffected; just record it and re-anchor. */
    suspend fun onSystemTimeChanged() {
        val cfg = dao.get()
        if (cfg?.active == true) log.log(SecurityEventType.TIME_CHANGED, "")
        checkpoint()
    }

    /** After reboot or app update: re-anchor time, re-register geofences, restore the filter if possible. */
    suspend fun recoverAfterRestart() {
        val before = dao.get()
        val rebooted = before?.active == true && before.bootCount != bootCount()
        val cfg = checkpoint()
        val zones = db.safeZoneDao().getAll()
        val geofencesOk = geofences.sync(cfg, zones)

        if (cfg?.active == true) {
            if (rebooted) log.log(SecurityEventType.REBOOT_RECOVERY, "")
            val remaining = LockEvaluator.snapshot(cfg, SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount()).remainingMs
            if (LockMode.fromName(cfg.mode).usesTime && remaining > 0) WorkScheduler.scheduleUnlock(context, remaining)
            if (!geofencesOk && LockMode.fromName(cfg.mode).usesDistance) {
                notifier.alert(
                    Notifier.ID_BOOT,
                    context.getString(R.string.notif_geofence_title),
                    context.getString(R.string.notif_geofence_text)
                )
            }
        }
        WorkScheduler.schedulePeriodic(context)

        val s = settings.get()
        if (s.filterEnabled || cfg?.blockAdult == true && cfg.active) {
            if (VpnService.prepare(context) == null) {
                if (!PureLockVpnService.start(context, cfg?.isTest == true)) notifyFilterStopped()
            } else {
                notifyFilterStopped()
            }
        }
    }

    private fun notifyFilterStopped() {
        notifier.alert(
            Notifier.ID_BOOT,
            context.getString(R.string.notif_filter_stopped_title),
            context.getString(R.string.notif_filter_stopped_text)
        )
    }
}
