package com.purelock.app.data

import android.content.Context
import com.purelock.app.Graph
import com.purelock.app.data.db.FocusProfile
import kotlinx.coroutines.flow.Flow

/**
 * Profiles data layer (SPEC S01 §11–15, S12/S13). Thin wrapper over
 * [com.purelock.app.data.db.FocusProfileDao] — the Dashboard (Area A) reads profiles
 * through this, the create flow saves through it, and the accessibility service
 * reads the active profiles + the schedule-only extras through it.
 *
 * Two schedule values cannot live in Room (the DB is at v4 and must not change):
 * - "Combined" (Usage Limit applies to the selected apps' total time, not each app)
 *   is kept in SharedPreferences (`purelock_profiles`, key `combined_<id>`).
 * - A Quick Block profile's end time is kept in the same prefs (`quick_until_<id>`),
 *   armed when the profile is activated and cleared when it is deactivated, so a
 *   service restart does not re-arm the block from "now".
 */
class ProfileRepository(private val context: Context) {

    private val dao get() = Graph.db.profileDao()

    private val prefs get() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeAll(): Flow<List<FocusProfile>> = dao.observeAll()

    fun observeActive(): Flow<List<FocusProfile>> = dao.observeActive()

    suspend fun getAll(): List<FocusProfile> = dao.getAll()

    suspend fun get(id: Long): FocusProfile? = dao.get(id)

    /** Inserts or replaces a profile; returns the row id (new for a fresh profile). */
    suspend fun upsert(profile: FocusProfile): Long = dao.upsert(profile)

    suspend fun delete(id: Long) {
        dao.delete(id)
        clearQuickBlock(id)
        setCombined(id, false)
    }

    suspend fun setActive(id: Long, active: Boolean) = dao.setActive(id, active)

    suspend fun setPaused(id: Long, paused: Boolean) = dao.setPaused(id, paused)

    /**
     * Activates exactly one profile: every other active profile is deactivated first
     * (single-active invariant — only one profile's rules are enforced at a time).
     * Arms the Quick Block end time when the profile is a Quick Block profile.
     */
    suspend fun activate(id: Long) {
        val all = dao.getAll()
        for (profile in all) {
            if (profile.active && profile.id != id) {
                dao.setActive(profile.id, false)
                clearQuickBlock(profile.id)
            }
        }
        dao.setActive(id, true)
        dao.get(id)?.let { armQuickBlock(it) }
    }

    /** Deactivates a profile and clears its armed Quick Block end time. */
    suspend fun deactivate(id: Long) {
        dao.setActive(id, false)
        clearQuickBlock(id)
    }

    // ------------------------------------------------------------------ Combined

    /** True when this profile's Usage Limit applies to its apps' combined time. */
    fun isCombined(profileId: Long): Boolean =
        prefs.getBoolean(KEY_COMBINED + profileId, false)

    fun setCombined(profileId: Long, combined: Boolean) {
        prefs.edit().putBoolean(KEY_COMBINED + profileId, combined).apply()
    }

    // ------------------------------------------------------------------ Quick Block

    /**
     * The wall-clock epoch a Quick Block profile stops blocking at (0 = none armed).
     * Armed by [activate]; survives a service restart, so the block is not re-armed
     * from "now" every time the service re-collects the active profiles.
     */
    fun quickBlockUntilEpoch(profileId: Long): Long =
        prefs.getLong(KEY_QUICK_UNTIL + profileId, 0L)

    private fun armQuickBlock(profile: FocusProfile) {
        if (profile.conditionType == "QUICK_BLOCK" && profile.quickBlockMinutes > 0) {
            val until = System.currentTimeMillis() + profile.quickBlockMinutes * 60_000L
            prefs.edit().putLong(KEY_QUICK_UNTIL + profile.id, until).apply()
        }
    }

    private fun clearQuickBlock(profileId: Long) {
        prefs.edit().remove(KEY_QUICK_UNTIL + profileId).apply()
    }

    companion object {
        private const val PREFS_NAME = "purelock_profiles"
        private const val KEY_COMBINED = "combined_"
        private const val KEY_QUICK_UNTIL = "quick_until_"
    }
}
