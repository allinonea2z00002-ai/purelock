package com.purelock.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val filterSourceUrl: String = SettingsStore.DEFAULT_FILTER_URL,
    // --- Area F: Settings screen (S23) toggles/values, owned by the settings area. ---
    /** Global pause: blocking + monitoring suspended until resumed (S23 "Pause PureLock"). */
    val paused: Boolean = false,
    val allowHomeRotation: Boolean = false,
    /** Skip the pause-duration picker; a paused profile stays paused until resumed. */
    val enablePauseDuration: Boolean = true,
    val lowTimeAlert: Boolean = true,
    val pomodoroBreakNotify: Boolean = true,
    /** "onscreen" | "notification" | "hidden" — how remaining time is displayed. */
    val remainingTimerType: String = "onscreen",
    /** Minutes after midnight the user's day starts (S23 "Set Start of Day"). */
    val startOfDayMinutes: Int = 0,
    /** Custom quote shown on the block screen; blank = the built-in rotation. */
    val motivationalQuote: String = "",
    /** URL opened from the block screen; blank = none. */
    val redirectUrl: String = "",
    /**
     * Stay Focused mode (SPEC S04): "NORMAL" (default), "LOCK" or "STRICT".
     * Owned by the modes area; the Dashboard mode card reads the same value.
     */
    val mode: String = AppMode.NORMAL,
    /** Lock wizard step 1 (S05): what Lock mode password-protects. */
    val lockBlockApp: Boolean = false,
    val lockBlockRestrictionEditing: Boolean = true,
    val lockBlockPhoneSettings: Boolean = false,
    /** Strict wizard step 1 (S06): "ALL" or "SPECIFIC". */
    val strictRestrictionEditing: String = StrictRestrictionEditing.ALL,
    /** Strict wizard step 3 (S06): "PIN", "PARTNER_PIN" or "TIMER". */
    val strictDeactivation: String = StrictDeactivation.PIN,
    /** Strict wizard step 2 (S06) — same keys the Settings screen (S23) uses. */
    val blockPowerOff: Boolean = false,
    val blockRecentApps: Boolean = false,
    val blockSplitScreen: Boolean = true,
    val blockUnsupportedBrowsers: Boolean = false
)

/** Mode values stored in [SettingsKeys.MODE]. */
object AppMode {
    const val NORMAL = "NORMAL"
    const val LOCK = "LOCK"
    const val STRICT = "STRICT"
}

/** Strict wizard step 1 choice (S06): what restriction editing is blocked. */
object StrictRestrictionEditing {
    const val ALL = "ALL"
    const val SPECIFIC = "SPECIFIC"
}

/** Strict wizard step 3 choice (S06): how Strict mode can be deactivated. */
object StrictDeactivation {
    const val PIN = "PIN"
    const val PARTNER_PIN = "PARTNER_PIN"
    const val TIMER = "TIMER"
}

object SettingsKeys {
    val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
    val SAFE_SEARCH = booleanPreferencesKey("safe_search")
    val UNINSTALL_PROTECTION = booleanPreferencesKey("uninstall_protection")
    val KEEP_FILTER_AFTER_LOCK = booleanPreferencesKey("keep_filter_after_lock")
    val INTERRUPTION_ALERTS = booleanPreferencesKey("interruption_alerts")
    val STORE_LOCATION_HISTORY = booleanPreferencesKey("store_location_history")
    val FILTER_SOURCE_URL = stringPreferencesKey("filter_source_url")
    // Area F: Settings screen (S23).
    val PAUSED = booleanPreferencesKey("paused")
    val ALLOW_HOME_ROTATION = booleanPreferencesKey("allow_home_rotation")
    val ENABLE_PAUSE_DURATION = booleanPreferencesKey("enable_pause_duration")
    val LOW_TIME_ALERT = booleanPreferencesKey("low_time_alert")
    val POMODORO_BREAK_NOTIFY = booleanPreferencesKey("pomodoro_break_notify")
    val REMAINING_TIMER_TYPE = stringPreferencesKey("remaining_timer_type")
    val START_OF_DAY_MINUTES = intPreferencesKey("start_of_day_minutes")
    val MOTIVATIONAL_QUOTE = stringPreferencesKey("motivational_quote")
    val REDIRECT_URL = stringPreferencesKey("redirect_url")
    // Modes area (SPEC S04–S06).
    val MODE = stringPreferencesKey("mode")
    val LOCK_BLOCK_APP = booleanPreferencesKey("lock_block_app")
    val LOCK_BLOCK_RESTRICTION_EDITING = booleanPreferencesKey("lock_block_restriction_editing")
    val LOCK_BLOCK_PHONE_SETTINGS = booleanPreferencesKey("lock_block_phone_settings")
    val STRICT_RESTRICTION_EDITING = stringPreferencesKey("strict_restriction_editing")
    val STRICT_DEACTIVATION = stringPreferencesKey("strict_deactivation")
    val BLOCK_POWER_OFF = booleanPreferencesKey("block_power_off")
    val BLOCK_RECENT_APPS = booleanPreferencesKey("block_recent_apps")
    val BLOCK_SPLIT_SCREEN = booleanPreferencesKey("block_split_screen")
    val BLOCK_UNSUPPORTED_BROWSERS = booleanPreferencesKey("block_unsupported_browsers")
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
            filterSourceUrl = p[SettingsKeys.FILTER_SOURCE_URL] ?: DEFAULT_FILTER_URL,
            paused = p[SettingsKeys.PAUSED] ?: false,
            allowHomeRotation = p[SettingsKeys.ALLOW_HOME_ROTATION] ?: false,
            enablePauseDuration = p[SettingsKeys.ENABLE_PAUSE_DURATION] ?: true,
            lowTimeAlert = p[SettingsKeys.LOW_TIME_ALERT] ?: true,
            pomodoroBreakNotify = p[SettingsKeys.POMODORO_BREAK_NOTIFY] ?: true,
            remainingTimerType = p[SettingsKeys.REMAINING_TIMER_TYPE] ?: "onscreen",
            startOfDayMinutes = p[SettingsKeys.START_OF_DAY_MINUTES] ?: 0,
            motivationalQuote = p[SettingsKeys.MOTIVATIONAL_QUOTE] ?: "",
            redirectUrl = p[SettingsKeys.REDIRECT_URL] ?: "",
            mode = p[SettingsKeys.MODE] ?: AppMode.NORMAL,
            lockBlockApp = p[SettingsKeys.LOCK_BLOCK_APP] ?: false,
            lockBlockRestrictionEditing = p[SettingsKeys.LOCK_BLOCK_RESTRICTION_EDITING] ?: true,
            lockBlockPhoneSettings = p[SettingsKeys.LOCK_BLOCK_PHONE_SETTINGS] ?: false,
            strictRestrictionEditing = p[SettingsKeys.STRICT_RESTRICTION_EDITING]
                ?: StrictRestrictionEditing.ALL,
            strictDeactivation = p[SettingsKeys.STRICT_DEACTIVATION] ?: StrictDeactivation.PIN,
            blockPowerOff = p[SettingsKeys.BLOCK_POWER_OFF] ?: false,
            blockRecentApps = p[SettingsKeys.BLOCK_RECENT_APPS] ?: false,
            blockSplitScreen = p[SettingsKeys.BLOCK_SPLIT_SCREEN] ?: true,
            blockUnsupportedBrowsers = p[SettingsKeys.BLOCK_UNSUPPORTED_BROWSERS] ?: false
        )
    }

    /** Emits the current Stay Focused mode ("NORMAL" / "LOCK" / "STRICT"). */
    fun observeMode(): Flow<String> = flow.map { it.mode }

    /** Switches the Stay Focused mode (SPEC S04). */
    suspend fun setMode(mode: String) {
        set(SettingsKeys.MODE, mode)
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
