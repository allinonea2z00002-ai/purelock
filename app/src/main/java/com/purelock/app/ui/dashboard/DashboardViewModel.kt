package com.purelock.app.ui.dashboard

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.util.DayUtils
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dashboard (SPEC S01) state. All numbers are real: today's screen time / launches
 * come from UsageStatsManager via [Graph.usage], deltas are vs yesterday (read the
 * same way — [Graph.usage] has no yesterday API), and the block counts are today's
 * rows in the focus_statistics table (recorded by the accessibility service).
 *
 * Deltas are honest: a day that is still in progress is usually *below* yesterday's
 * full-day total, so a negative delta is shown with a "−" prefix instead of the
 * screenshot's "+". The red colour is kept either way, as in the reference.
 *
 * Dashboard sections respect the Organise Dashboard visibility flags (Area F):
 * SharedPreferences "purelock_ui", key "dash_sections", a comma-separated string
 * of the visible section keys in [com.purelock.app.ui.drawer.DashSections].
 * Absent (the user never opened Organise Dashboard) means ALL sections visible.
 * The pink permission banner is not a section: it shows whenever Accessibility
 * is off, regardless of the flags.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    enum class ModeLevel { NORMAL, LOCK, STRICT }

    data class DashboardState(
        val accessibilityOn: Boolean = false,
        val screenTimeText: String = DurationFormat.formatHrsMins(0),
        val screenTimeDeltaText: String = DurationFormat.formatDeltaPercent(0),
        val launchesText: String = "0",
        val launchesDeltaText: String = DurationFormat.formatDeltaLaunches(0),
        val appBlocksToday: Int = 0,
        val websiteBlocksToday: Int = 0,
        val keywordsEnabled: Boolean = false,
        val adultEnabled: Boolean = false,
        val reelsEnabled: Boolean = false,
        val profiles: List<FocusProfile> = emptyList(),
        val modeLevel: ModeLevel = ModeLevel.NORMAL,
        val strictAlarmCount: Int = 0,
        val visibleSections: Set<String> = SECTION_KEYS
    )

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    init {
        viewModelScope.launch {
            combine(
                Graph.db.profileDao().observeAll(),
                Graph.db.statsDao().observeAll(),
                Graph.settings.flow,
                com.purelock.app.data.prefs.StrictAlarmStore(getApplication()).flow
            ) { profiles, statsRows, settings, alarms ->
                val todayKey = DayUtils.dateKey(System.currentTimeMillis())
                val today = statsRows.firstOrNull { it.dateKey == todayKey }
                Triple(profiles, today, settings) to alarms.count { it.enabled }
            }.collect { (triple, enabledAlarms) ->
                val (profiles, today, settings) = triple
                _state.value = _state.value.copy(
                    profiles = profiles,
                    appBlocksToday = today?.appBlocks ?: 0,
                    websiteBlocksToday = today?.websiteBlocks ?: 0,
                    adultEnabled = settings.filterEnabled,
                    // The mode lives in SettingsStore (modes area); the Dashboard
                    // mode card reads the same value ModesFragment writes.
                    modeLevel = modeLevelOf(settings.mode),
                    strictAlarmCount = enabledAlarms
                )
            }
        }
        refresh()
    }

    /** Re-reads the synchronous / usage-stats parts (call from onResume). */
    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val accessibilityOn = Graph.integrity.isAccessibilityEnabled()
            val keywordsEnabled =
                com.purelock.app.data.prefs.ContentFilterStore.keywordsEnabled(ctx)
            val reelsEnabled =
                com.purelock.app.data.prefs.ContentFilterStore.reelsEnabled(ctx)
            val visibleSections = readVisibleSections(ctx)
            val (todayMs, todayLaunches, yesterdayMs, yesterdayLaunches) =
                withContext(Dispatchers.IO) { readUsageTotals(ctx) }
            _state.value = _state.value.copy(
                accessibilityOn = accessibilityOn,
                screenTimeText = DurationFormat.formatHrsMins(todayMs),
                screenTimeDeltaText = deltaPercentText(todayMs, yesterdayMs),
                launchesText = todayLaunches.toString(),
                launchesDeltaText = deltaLaunchesText(todayLaunches, yesterdayLaunches),
                keywordsEnabled = keywordsEnabled,
                reelsEnabled = reelsEnabled,
                visibleSections = visibleSections
            )
        }
    }

    fun setKeywordsEnabled(enabled: Boolean) {
        com.purelock.app.data.prefs.ContentFilterStore.setKeywordsEnabled(
            getApplication(), enabled
        )
        _state.value = _state.value.copy(keywordsEnabled = enabled)
    }

    fun setReelsEnabled(enabled: Boolean) {
        com.purelock.app.data.prefs.ContentFilterStore.setReelsEnabled(
            getApplication(), enabled
        )
        _state.value = _state.value.copy(reelsEnabled = enabled)
    }

    fun setAdultEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Graph.settings.set(SettingsKeys.FILTER_ENABLED, enabled)
        }
        _state.value = _state.value.copy(adultEnabled = enabled)
    }

    /** Activates one profile; every other profile is deactivated (only one enforces at a time). */
    fun activateProfile(profile: FocusProfile) {
        viewModelScope.launch {
            val dao = Graph.db.profileDao()
            dao.getAll()
                .filter { it.id != profile.id && it.active }
                .forEach { dao.setActive(it.id, false) }
            dao.setActive(profile.id, true)
        }
    }

    fun setProfilePaused(profile: FocusProfile, paused: Boolean) {
        viewModelScope.launch { Graph.db.profileDao().setPaused(profile.id, paused) }
    }

    fun deleteProfile(profile: FocusProfile) {
        viewModelScope.launch { Graph.db.profileDao().delete(profile.id) }
    }

    // ------------------------------------------------------------------ usage

    private data class UsageTotals(
        val todayMs: Long,
        val todayLaunches: Int,
        val yesterdayMs: Long,
        val yesterdayLaunches: Int
    )

    private fun readUsageTotals(ctx: Context): UsageTotals {
        if (!Graph.usage.hasAccess()) return UsageTotals(0, 0, 0, 0)
        val day = Graph.usage.dayUsage()
        val todayLaunches = day.launches.sumOf { it.launches }
        val (yesterdayMs, yesterdayLaunches) = yesterdayTotals(ctx)
        return UsageTotals(day.totalMs, todayLaunches, yesterdayMs, yesterdayLaunches)
    }

    /** Yesterday's totals, read directly from UsageStatsManager (Graph.usage has no yesterday API). */
    private fun yesterdayTotals(ctx: Context): Pair<Long, Int> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterdayStart = todayStart - 86_400_000L
        val totalMs = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, yesterdayStart, todayStart)
        }.getOrNull().orEmpty().sumOf { it.totalTimeInForeground }
        var launches = 0
        runCatching {
            val events = usm.queryEvents(yesterdayStart, todayStart)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) launches++
            }
        }
        return totalMs to launches
    }

    private fun deltaPercentText(todayMs: Long, yesterdayMs: Long): String {
        if (yesterdayMs <= 0) {
            return if (todayMs > 0) DurationFormat.formatDeltaPercent(100)
            else DurationFormat.formatDeltaPercent(0)
        }
        val delta = ((todayMs - yesterdayMs) * 100 / yesterdayMs).toInt()
        return if (delta >= 0) DurationFormat.formatDeltaPercent(delta)
        else "-${-delta} percent"
    }

    private fun deltaLaunchesText(today: Int, yesterday: Int): String {
        val delta = today - yesterday
        return if (delta >= 0) DurationFormat.formatDeltaLaunches(delta)
        else "-${-delta} launches"
    }

    /** Maps the SettingsStore mode string ("NORMAL"/"LOCK"/"STRICT") to [ModeLevel]. */
    private fun modeLevelOf(mode: String): ModeLevel = when (mode.uppercase()) {
        "LOCK" -> ModeLevel.LOCK
        "STRICT" -> ModeLevel.STRICT
        else -> ModeLevel.NORMAL
    }

    private fun readVisibleSections(ctx: Context): Set<String> =
        com.purelock.app.ui.drawer.DashSections.visibleSections(ctx)

    companion object {
        const val KEY_DASH_SECTIONS = "dash_sections"

        /** Organise Dashboard section keys (Area F contract, [DashSections.ALL]). */
        val SECTION_KEYS: Set<String> =
            com.purelock.app.ui.drawer.DashSections.ALL.toSet()
    }
}
