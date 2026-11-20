package com.purelock.app.service

import android.content.Context
import com.purelock.app.Graph
import com.purelock.app.domain.CombineRule
import com.purelock.app.domain.LockManager
import com.purelock.app.domain.LockMode

/**
 * Take a Break engine (SPEC S02/S03).
 *
 * Starting a break starts a real time lock through [LockManager] (so the lock survives
 * process death, reboot recovery and the normal unlock flow), and records the break's
 * own state in SharedPreferences ("purelock_break"). While a break is active,
 * [PureLockAccessibilityService] blocks every foreground app except the whitelisted
 * ones (see [shouldBlockNow]); the whitelist is read from these same prefs, so the
 * service never touches the database on the event path.
 *
 * Pomodoro: the lock covers the whole span — sessions × focus + (sessions − 1) × break.
 * During a break phase the phone is NOT blocked ([shouldBlockNow] is false), so the
 * user can use it between focus sessions; during a focus phase everything but the
 * whitelist is blocked.
 *
 * Honest limits:
 * - "Block Notifications" is stored ([BreakState.blockNotifications]) but cannot be
 *   enforced without a NotificationListenerService, which this app does not have.
 * - "Pause" pauses *blocking* (the countdown keeps running on the underlying lock,
 *   because LockManager has no pause); ending a break early ends the lock through
 *   [LockManager.emergencyOverride], which is logged as such.
 */
object BreakManager {

    private const val FILE = "purelock_break"
    private const val KEY_ACTIVE = "active"
    private const val KEY_END_EPOCH = "end_epoch"
    private const val KEY_TOTAL_MS = "total_ms"
    private const val KEY_POMODORO = "pomodoro"
    private const val KEY_FOCUS_MIN = "focus_min"
    private const val KEY_BREAK_MIN = "break_min"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_HIDE_PAUSE = "hide_pause"
    private const val KEY_BLOCK_NOTIFICATIONS = "block_notifications"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_PAUSED = "paused"

    data class PomodoroConfig(val focusMinutes: Int, val breakMinutes: Int, val sessions: Int) {
        /** Whole-span length: focus, break, focus … — no trailing break after the last focus. */
        val totalMs: Long
            get() = (sessions.toLong() * focusMinutes + (sessions - 1).toLong() * breakMinutes) * 60_000L
    }

    data class BreakState(
        val endEpochMs: Long,
        val totalMs: Long,
        val pomodoro: PomodoroConfig?,
        val hidePause: Boolean,
        val blockNotifications: Boolean,
        val whitelist: Set<String>,
        val paused: Boolean
    ) {
        val remainingMs: Long get() = (endEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True while a break is running. Expired state is cleared lazily on read. */
    fun isActive(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        if (p.getLong(KEY_END_EPOCH, 0L) <= System.currentTimeMillis()) {
            p.edit().clear().apply()
            return false
        }
        return true
    }

    fun state(context: Context): BreakState? {
        if (!isActive(context)) return null
        val p = prefs(context)
        val pomodoro = if (p.getBoolean(KEY_POMODORO, false)) {
            PomodoroConfig(
                focusMinutes = p.getInt(KEY_FOCUS_MIN, 0),
                breakMinutes = p.getInt(KEY_BREAK_MIN, 0),
                sessions = p.getInt(KEY_SESSIONS, 0)
            )
        } else {
            null
        }
        return BreakState(
            endEpochMs = p.getLong(KEY_END_EPOCH, 0L),
            totalMs = p.getLong(KEY_TOTAL_MS, 0L),
            pomodoro = pomodoro,
            hidePause = p.getBoolean(KEY_HIDE_PAUSE, false),
            blockNotifications = p.getBoolean(KEY_BLOCK_NOTIFICATIONS, false),
            whitelist = p.getStringSet(KEY_WHITELIST, emptySet()).orEmpty(),
            paused = p.getBoolean(KEY_PAUSED, false)
        )
    }

    /** Whitelisted packages (allowed during the break). Empty when no break is active. */
    fun whitelist(context: Context): Set<String> = state(context)?.whitelist.orEmpty()

    /**
     * True when a foreground app that is not whitelisted must be blocked right now:
     * a break is running, it is not paused, and (for Pomodoro) the current phase is a
     * focus phase rather than a rest break.
     */
    fun shouldBlockNow(context: Context): Boolean {
        val s = state(context) ?: return false
        if (s.paused) return false
        val pomodoro = s.pomodoro ?: return true
        return isFocusPhase(s, System.currentTimeMillis(), pomodoro)
    }

    /** Focus phase while elapsed time inside the current cycle is under the focus length. */
    fun isFocusPhase(state: BreakState, nowMs: Long, pomodoro: PomodoroConfig): Boolean {
        val elapsedMs = state.totalMs - state.remainingMsAt(nowMs)
        if (elapsedMs < 0) return true
        val cycleMs = (pomodoro.focusMinutes + pomodoro.breakMinutes) * 60_000L
        if (cycleMs <= 0) return true
        val withinCycleMs = elapsedMs % cycleMs
        return withinCycleMs < pomodoro.focusMinutes * 60_000L
    }

    private fun BreakState.remainingMsAt(nowMs: Long): Long =
        (endEpochMs - nowMs).coerceAtLeast(0L)

    /**
     * Starts a break: first starts the underlying time lock, then records the break.
     * Returns false when the lock could not start (e.g. a lock is already active, or
     * the duration is outside LockManager's valid range) — in that case no break state
     * is written. A duration of 0 or less is rejected outright.
     */
    suspend fun startBreak(
        context: Context,
        durationMs: Long,
        pomodoro: PomodoroConfig?,
        hidePause: Boolean,
        blockNotifications: Boolean,
        whitelist: Set<String>
    ): Boolean {
        val totalMs = pomodoro?.totalMs ?: durationMs
        if (totalMs <= 0) return false
        if (isActive(context)) endBreak(context)
        val result = Graph.lockManager.start(
            LockManager.StartRequest(
                mode = LockMode.TIME,
                durationMs = totalMs,
                distanceM = 0,
                rule = CombineRule.ANY,
                startLat = null,
                startLng = null,
                blockAdult = true,
                blockApps = true
            )
        )
        if (result != LockManager.StartResult.Started) return false
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_END_EPOCH, System.currentTimeMillis() + totalMs)
            .putLong(KEY_TOTAL_MS, totalMs)
            .putBoolean(KEY_POMODORO, pomodoro != null)
            .putInt(KEY_FOCUS_MIN, pomodoro?.focusMinutes ?: 0)
            .putInt(KEY_BREAK_MIN, pomodoro?.breakMinutes ?: 0)
            .putInt(KEY_SESSIONS, pomodoro?.sessions ?: 0)
            .putBoolean(KEY_HIDE_PAUSE, hidePause)
            .putBoolean(KEY_BLOCK_NOTIFICATIONS, blockNotifications)
            .putStringSet(KEY_WHITELIST, whitelist.toSet())
            .putBoolean(KEY_PAUSED, false)
            .apply()
        return true
    }

    /**
     * Ends the break early. Also ends the underlying time lock (via the emergency
     * override path — LockManager exposes no other "finish now" call), but only when
     * this break actually owns a running lock; a stale/expired break just clears state.
     */
    suspend fun endBreak(context: Context) {
        val wasActive = isActive(context)
        prefs(context).edit().clear().apply()
        if (wasActive) {
            runCatching { Graph.lockManager.emergencyOverride() }
        }
    }

    /** Pauses/resumes blocking for the running break (the lock countdown keeps running). */
    fun setPaused(context: Context, paused: Boolean) {
        if (!isActive(context)) return
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }
}
