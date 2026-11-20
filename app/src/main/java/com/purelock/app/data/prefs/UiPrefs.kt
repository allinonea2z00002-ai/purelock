package com.purelock.app.data.prefs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Theme choice, read synchronously before the first frame so the right theme is applied without flicker. */
object UiPrefs {
    private const val FILE = "purelock_ui"
    private const val KEY_THEME = "theme"

    const val THEME_DARK = "dark"
    const val THEME_WHITE = "white"

    fun theme(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME, THEME_DARK) ?: THEME_DARK

    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme).apply()
    }

    /** Currently selected app language tag ("en" or "bn"). */
    fun language(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (!locales.isEmpty) locales[0]?.language ?: "en" else "en"
    }

    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
