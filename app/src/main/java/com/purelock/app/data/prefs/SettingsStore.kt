package com.purelock.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "purelock_settings")

data class AppSettings(
    /**
     * Website content blocking master toggle. Enforcement is the accessibility
     * URL-watch (see PureLockAccessibilityService) plus the phone's Private DNS;
     * there is no VPN any more. The DataStore key is unchanged ("filter_enabled")
     * so no migration is needed.
     */
    val filterEnabled: Boolean = false,
    val safeSearch: Boolean = true,
    val uninstallProtection: Boolean = true,
    val keepFilterAfterLock: Boolean = false,
    val interruptionAlerts: Boolean = true,
    val storeLocationHistory: Boolean = true,
    val filterSourceUrl: String = SettingsStore.DEFAULT_FILTER_URL
)

object SettingsKeys {
    val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
    val SAFE_SEARCH = booleanPreferencesKey("safe_search")
    val UNINSTALL_PROTECTION = booleanPreferencesKey("uninstall_protection")
    val KEEP_FILTER_AFTER_LOCK = booleanPreferencesKey("keep_filter_after_lock")
    val INTERRUPTION_ALERTS = booleanPreferencesKey("interruption_alerts")
    val STORE_LOCATION_HISTORY = booleanPreferencesKey("store_location_history")
    val FILTER_SOURCE_URL = stringPreferencesKey("filter_source_url")
}

/** App preferences kept in DataStore. (Theme and language live in [UiPrefs] because they are needed synchronously.) */
class SettingsStore(private val context: Context) {

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            filterEnabled = p[SettingsKeys.FILTER_ENABLED] ?: false,
            safeSearch = p[SettingsKeys.SAFE_SEARCH] ?: true,
            uninstallProtection = p[SettingsKeys.UNINSTALL_PROTECTION] ?: true,
            keepFilterAfterLock = p[SettingsKeys.KEEP_FILTER_AFTER_LOCK] ?: false,
            interruptionAlerts = p[SettingsKeys.INTERRUPTION_ALERTS] ?: true,
            storeLocationHistory = p[SettingsKeys.STORE_LOCATION_HISTORY] ?: true,
            filterSourceUrl = p[SettingsKeys.FILTER_SOURCE_URL] ?: DEFAULT_FILTER_URL
        )
    }

    suspend fun get(): AppSettings = flow.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    companion object {
        /** A public, community-maintained hosts list of adult domains. Only a plain GET is made; nothing is uploaded. */
        const val DEFAULT_FILTER_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
    }
}
