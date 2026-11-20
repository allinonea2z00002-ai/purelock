package com.purelock.app.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** One app open: the package, when it came to the foreground, and how long it stayed there. */
data class TimelineEvent(val packageName: String, val timestampMs: Long, val durationMs: Long)

/** Foreground time for one app. */
data class AppUsage(val packageName: String, val usageMs: Long)

/** Foreground opens for one app. */
data class AppLaunches(val packageName: String, val launches: Int)

/** Everything the Analytics screen needs for today, read once per refresh. */
data class UsageDay(
    val totalMs: Long,
    val hourlyMs: LongArray(24),
    val perApp: List<AppUsage>,
    val launches: List<AppLaunches>,
    val browsingMs: Long,
    val browsingPerApp: List<AppUsage>,
    val unlocks: Int,
    val timeline: List<TimelineEvent>
)

/** App usage for daily limits, read from Android's UsageStats (needs the user's "Usage access" grant). */
class UsageRepository(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val cache = ConcurrentHashMap<String, Pair<Long, Long>>() // package -> (read at, usage ms)

    /** Today's raw usage events, cached for 30 seconds (an events query is the slow part). */
    @Volatile private var eventsCache: Pair<Long, List<RawEvent>>? = null

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground time today in milliseconds (cached for 30 seconds per app). Returns 0 without usage access. */
    fun todayUsageMs(packageName: String): Long {
        if (!hasAccess()) return 0L
        val now = System.currentTimeMillis()
        cache[packageName]?.let { (at, ms) -> if (now - at < 30_000) return ms }
        val usage = readToday(now)[packageName] ?: 0L
        cache[packageName] = now to usage
        return usage
    }

    /** Today's usage for every app, for display on the app-management screen. */
    fun allToday(): Map<String, Long> = if (hasAccess()) readToday(System.currentTimeMillis()) else emptyMap()

    private fun readToday(now: Long): Map<String, Long> {
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return usm.queryAndAggregateUsageStats(startOfDay, now)
            .mapValues { it.value.totalTimeInForeground }
    }

    // ------------------------------------------------------------------ events

    /** A copied-out usage event. UsageEvents.Event objects are reused by the iterator, so the values are copied. */
    private data class RawEvent(val packageName: String, val timeMs: Long, val type: Int)

    private fun startOfTodayMs(now: Long): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Today's events (foreground/background/screen), newest query at most every 30 seconds. */
    private fun todayEvents(now: Long): List<RawEvent> {
        if (!hasAccess()) return emptyList()
        eventsCache?.let { (at, events) -> if (now - at < 30_000) return events }
        val events = queryEvents(startOfTodayMs(now), now)
        eventsCache = now to events
        return events
    }

    private fun queryEvents(startMs: Long, endMs: Long): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        val events = usm.queryEvents(startMs, endMs) ?: return out
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            out.add(RawEvent(pkg, event.timeStamp, event.eventType))
        }
        return out
    }

    /**
     * Foreground intervals (package, fromMs, toMs) for today, derived from
     * MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND events. An interval still open at [nowMs] is
     * closed at [nowMs]. Intervals are clipped to [startMs, endMs].
     */
    private fun foregroundIntervals(startMs: Long, endMs: Long, nowMs: Long): List<Triple<String, Long, Long>> {
        val events = todayEvents(nowMs).filter { it.timeMs in startMs..endMs }
        val open = HashMap<String, Long>()
        val out = ArrayList<Triple<String, Long, Long>>()
        fun close(pkg: String, atMs: Long) {
            val from = open.remove(pkg) ?: return
            if (atMs > from) out.add(Triple(pkg, from, atMs))
        }
        for (e in events) {
            when (e.type) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // Only one app is in the foreground: close whatever was open.
                    open.keys.toList().forEach { close(it, e.timeMs) }
                    open[e.packageName] = e.timeMs
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    // No foreground event in this window means the app was already
                    // open when the window started.
                    if (e.packageName !in open) open[e.packageName] = startMs
                    close(e.packageName, e.timeMs)
                }
            }
        }
        val closeAt = minOf(nowMs, endMs)
        open.keys.toList().forEach { close(it, closeAt) }
        return out
    }

    /** Foreground time for one app since [sinceMs]. Event-based, so it works for any window (e.g. this hour). */
    fun usageSinceMs(packageName: String, sinceMs: Long): Long {
        if (!hasAccess()) return 0L
        val now = System.currentTimeMillis()
        return foregroundIntervals(sinceMs, now, now)
            .filter { it.first == packageName }
            .sumOf { it.third - it.second }
    }

    /** Foreground time for one app in the last 60 minutes. */
    fun usageThisHourMs(packageName: String): Long =
        usageSinceMs(packageName, System.currentTimeMillis() - 3_600_000L)

    /** Foreground time per hour of today (index 0 = 00:00-01:00), from foreground intervals split at hour boundaries. */
    fun hourlyUsageToday(): LongArray {
        val out = LongArray(24)
        if (!hasAccess()) return out
        val now = System.currentTimeMillis()
        val startOfDay = startOfTodayMs(now)
        val zone = ZoneId.systemDefault()
        for ((_, from, to) in foregroundIntervals(startOfDay, now, now)) {
            var cursor = maxOf(from, startOfDay)
            while (cursor < to) {
                val hourStart = LocalDate.now().atStartOfDay(zone).plusHours(((cursor - startOfDay) / 3_600_000L))
                    .toInstant().toEpochMilli()
                val hourEnd = hourStart + 3_600_000L
                val sliceEnd = minOf(to, hourEnd)
                out[((cursor - startOfDay) / 3_600_000L).toInt().coerceIn(0, 23)] += sliceEnd - cursor
                cursor = sliceEnd
            }
        }
        return out
    }

    /** How many times an app came to the foreground today. */
    fun launchesToday(packageName: String): Int {
        if (!hasAccess()) return 0
        val now = System.currentTimeMillis()
        return todayEvents(now).count {
            it.type == UsageEvents.Event.MOVE_TO_FOREGROUND && it.packageName == packageName
        }
    }

    /** How many times an app came to the foreground in the last 60 minutes. */
    fun launchesThisHour(packageName: String): Int {
        if (!hasAccess()) return 0
        val now = System.currentTimeMillis()
        val hourAgo = now - 3_600_000L
        return todayEvents(now).count {
            it.type == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                it.packageName == packageName && it.timeMs >= hourAgo
        }
    }

    /** Launches per app today, most-launched first. */
    fun allLaunchesToday(): List<AppLaunches> {
        if (!hasAccess()) return emptyList()
        val now = System.currentTimeMillis()
        return todayEvents(now)
            .filter { it.type == UsageEvents.Event.MOVE_TO_FOREGROUND }
            .groupingBy { it.packageName }.eachCount()
            .map { AppLaunches(it.key, it.value) }
            .sortedByDescending { it.launches }
    }

    /**
     * App opens today, newest first. The duration of an open is the time until the next app
     * comes to the foreground (or this app goes to the background); the newest open runs until now.
     */
    fun timelineToday(limit: Int = 100): List<TimelineEvent> {
        if (!hasAccess()) return emptyList()
        val now = System.currentTimeMillis()
        val events = todayEvents(now)
        val opens = events.filter { it.type == UsageEvents.Event.MOVE_TO_FOREGROUND }
        if (opens.isEmpty()) return emptyList()
        // For each open, the session ends at the next foreground/background event after it.
        val ends = events.filter {
            it.type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                it.type == UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        val out = ArrayList<TimelineEvent>(opens.size)
        for (open in opens) {
            val end = ends.firstOrNull { it.timeMs > open.timeMs }?.timeMs ?: now
            out.add(TimelineEvent(open.packageName, open.timeMs, (end - open.timeMs).coerceAtLeast(0)))
        }
        return out.sortedByDescending { it.timestampMs }.take(limit)
    }

    /**
     * "Screen Unlocks": how many times the screen became interactive today. UsageEvents exposes
     * SCREEN_INTERACTIVE (screen turned on), not a true keyguard-unlock event, so this counts
     * screen wake-ups — the closest UsageStats gets to unlocks.
     */
    fun screenUnlocksToday(): Int {
        if (!hasAccess()) return 0
        val now = System.currentTimeMillis()
        return todayEvents(now).count { it.type == UsageEvents.Event.SCREEN_INTERACTIVE }
    }

    // ------------------------------------------------------------------ browsing

    companion object {
        /** Packages counted as browsers for the Browsing Time tab. */
        val BROWSER_PACKAGES: Set<String> = setOf(
            "com.android.chrome",
            "com.android.browser",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.sec.android.app.sbrowser",
            "com.vivo.browser",
            "com.mi.globalbrowser"
        )
    }

    /** Total browsing time today (foreground time of known browser packages). */
    fun browsingTodayMs(): Long = browsingPerAppToday().sumOf { it.usageMs }

    /** Browsing time per browser app today, most-used first. */
    fun browsingPerAppToday(): List<AppUsage> {
        if (!hasAccess()) return emptyList()
        return readToday(System.currentTimeMillis())
            .filter { (pkg, ms) -> pkg in BROWSER_PACKAGES && ms > 0 }
            .map { AppUsage(it.key, it.value) }
            .sortedByDescending { it.usageMs }
    }

    /** One snapshot for the Analytics screen: total, per-hour, per-app, launches, browsing, unlocks, timeline. */
    fun dayUsage(): UsageDay {
        if (!hasAccess()) {
            return UsageDay(0, LongArray(24), emptyList(), emptyList(), 0, emptyList(), 0, emptyList())
        }
        val today = readToday(System.currentTimeMillis())
        val perApp = today.filter { it.value > 0 }
            .map { AppUsage(it.key, it.value) }
            .sortedByDescending { it.usageMs }
        val browsing = browsingPerAppToday()
        return UsageDay(
            totalMs = today.values.sum(),
            hourlyMs = hourlyUsageToday(),
            perApp = perApp,
            launches = allLaunchesToday(),
            browsingMs = browsing.sumOf { it.usageMs },
            browsingPerApp = browsing,
            unlocks = screenUnlocksToday(),
            timeline = timelineToday()
        )
    }
}
