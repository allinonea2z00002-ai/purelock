package com.purelock.app.ui.drawer

import android.content.Context

/**
 * Which Dashboard sections are visible (Organise Dashboard screen).
 *
 * Format — SharedPreferences file `"purelock_ui"`, key `"dash_sections"`:
 * a single comma-separated string of the VISIBLE section keys, in [ALL] order,
 * e.g. `"analytics,take_break,mode,quick_actions,profiles,templates"`.
 * - Key absent (never organised) = all sections visible (the default).
 * - Empty string = no sections visible.
 * - Unknown keys are ignored on read, so a newer/older build cannot break the list.
 *
 * Section keys match the Dashboard (S01) sections in order: the Analytics cards,
 * Take a Break card, Mode card, Quick Actions stack, Profiles list, and
 * Profile Templates. Area A (Dashboard) reads this via [visibleSections].
 */
object DashSections {
    const val FILE = "purelock_ui"
    const val KEY = "dash_sections"

    const val ANALYTICS = "analytics"
    const val TAKE_BREAK = "take_break"
    const val MODE = "mode"
    const val QUICK_ACTIONS = "quick_actions"
    const val PROFILES = "profiles"
    const val TEMPLATES = "templates"

    /** All section keys, in Dashboard order. */
    val ALL: List<String> = listOf(
        ANALYTICS, TAKE_BREAK, MODE, QUICK_ACTIONS, PROFILES, TEMPLATES
    )

    /** Visible section keys; defaults to [ALL] when nothing was saved yet. */
    fun visibleSections(context: Context): Set<String> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return ALL.toSet()
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it in ALL }.toSet()
    }

    /** Persists the visible section keys (stored in [ALL] order, comma-separated). */
    fun setVisibleSections(context: Context, sections: Set<String>) {
        val value = ALL.filter { it in sections }.joinToString(",")
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
