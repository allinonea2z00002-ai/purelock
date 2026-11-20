package com.purelock.app.ui.apps

import android.content.Context

/**
 * Pinned rows for the All Apps / All Sites sort orders (SPEC S07/S09 "Pinned").
 * No Room table exists for pins, so they live in a small SharedPreferences file.
 * Long-press a row to pin or unpin it.
 */
object PinStore {
    private const val FILE = "purelock_pins"

    const val KIND_APPS = "apps"
    const val KIND_SITES = "sites"

    fun pinned(context: Context, kind: String): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(kind, emptySet()).orEmpty().toSet()

    fun setPinned(context: Context, kind: String, key: String, pinned: Boolean) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(kind, emptySet()).orEmpty().toMutableSet()
        if (pinned) current.add(key) else current.remove(key)
        prefs.edit().putStringSet(kind, current).apply()
    }
}
