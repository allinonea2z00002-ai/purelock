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

/** Upstream DNS used by the local filter for queries that are allowed. */
enum class DnsProvider(val servers: List<String>) {
    /** The network's own resolver (no third party sees your DNS queries). */
    SYSTEM(emptyList()),
    /** CleanBrowsing "Adult Filter": a maintained family DNS that also blocks adult domains. */
    CLEANBROWSING_ADULT(listOf("185.228.168.10", "185.228.169.11")),
    /** Cloudflare for Families (malware + adult content). */
    CLOUDFLARE_FAMILY(listOf("1.1.1.3", "1.0.0.3"));

    companion object {
        fun fromName(name: String?): DnsProvider =
            values().firstOrNull { it.name == name } ?: SYSTEM
    }
}

data class AppSettings(
    val filterEnabled: Boolean = false,
    val dnsProvider: DnsProvider = DnsProvider.SYSTEM,
    val safeSearch: Boolean = true,
    val uninstallProtection: Boolean = true,
    val keepFilterAfterLock: Boolean = false,
    val interruptionAlerts: Boolean = true,
    val storeLocationHistory: Boolean = true,
    val filterSourceUrl: String = SettingsStore.DEFAULT_FILTER_URL
)

object SettingsKeys {
    val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
    val DNS_PROVIDER = stringPreferencesKey("dns_provider")
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
            dnsProvider = DnsProvider.fromName(p[SettingsKeys.DNS_PROVIDER]),
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
