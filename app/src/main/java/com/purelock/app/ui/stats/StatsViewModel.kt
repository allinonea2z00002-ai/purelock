package com.purelock.app.ui.stats

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.StatsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One row of a usage list (per-app usage, launches, or browsing), with its display label resolved. */
data class UsageAppRow(val packageName: String, val label: String, val usageMs: Long, val detail: String)

/** One timeline row: an app open with its timestamp and how long it lasted. */
data class TimelineRow(val packageName: String, val label: String, val timestampMs: Long, val durationMs: Long)

/** Today's usage analytics, all read from UsageStatsManager — no estimated or sample numbers. */
data class UsageAnalytics(
    val hasAccess: Boolean,
    val totalMs: Long,
    val hourlyMs: LongArray,
    val perApp: List<UsageAppRow>,
    val launches: List<UsageAppRow>,
    val browsingMs: Long,
    val browsingPerApp: List<UsageAppRow>,
    val unlocks: Int,
    val timeline: List<TimelineRow>,
    /** Foreground opens per hour (index 0 = 00:00-01:00), for the App Launches chart. */
    val hourlyLaunches: IntArray,
    /** Screen wake-ups per hour (index 0 = 00:00-01:00), for the Screen Unlocks chart. */
    val hourlyUnlocks: IntArray
)

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    /** Computed only from rows that were really recorded; an empty database shows zeros. */
    val summary: StateFlow<StatsSummary?> = Graph.db.statsDao().observeAll()
        .map { StatsCalculator.summarize(it, LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val completedSessions: StateFlow<Int> = Graph.db.sessionDao().observeCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _usage = MutableStateFlow<UsageAnalytics?>(null)
    val usage: StateFlow<UsageAnalytics?> = _usage.asStateFlow()

    init {
        refreshUsage()
    }

    /** Re-reads today's usage from UsageStatsManager (off the main thread). */
    fun refreshUsage() {
        viewModelScope.launch {
            val day = withContext(Dispatchers.IO) { Graph.usage.dayUsage() }
            val pm = getApplication<Application>().packageManager
            val labelOf: (String) -> String = { pkg ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull() ?: pkg
            }
            val perApp = withContext(Dispatchers.IO) {
                day.perApp.map { UsageAppRow(it.packageName, labelOf(it.packageName), it.usageMs, "") }
            }
            val launches = withContext(Dispatchers.IO) {
                day.launches.map {
                    UsageAppRow(it.packageName, labelOf(it.packageName), 0L, it.launches.toString())
                }
            }
            val browsing = withContext(Dispatchers.IO) {
                day.browsingPerApp.map { UsageAppRow(it.packageName, labelOf(it.packageName), it.usageMs, "") }
            }
            val timeline = withContext(Dispatchers.IO) {
                day.timeline.map { TimelineRow(it.packageName, labelOf(it.packageName), it.timestampMs, it.durationMs) }
            }
            _usage.value = UsageAnalytics(
                hasAccess = Graph.usage.hasAccess(),
                totalMs = day.totalMs,
                hourlyMs = day.hourlyMs,
                perApp = perApp,
                launches = launches,
                browsingMs = day.browsingMs,
                browsingPerApp = browsing,
                unlocks = day.unlocks,
                timeline = timeline,
                hourlyLaunches = day.hourlyLaunches,
                hourlyUnlocks = day.hourlyUnlocks
            )
        }
    }
}
