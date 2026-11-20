package com.purelock.app.ui.stats

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.Achievement
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.StatsSummary
import com.purelock.app.databinding.FragmentStatsBinding
import com.purelock.app.databinding.ItemSfSessionRowBinding
import com.purelock.app.databinding.ItemSfUsageRowBinding
import com.purelock.app.databinding.ItemUsageTimelineBinding
import com.purelock.app.databinding.SheetSfStatsFilterBinding
import com.purelock.app.service.UsageRepository
import com.purelock.app.ui.UiAnim
import com.purelock.app.ui.apps.AppListActivity
import com.purelock.app.ui.widget.BarChartView
import com.purelock.app.ui.widget.LineChartView
import com.purelock.app.util.DayUtils
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Analytics (Stay Focused S14–S19): gradient header with the "< Today >" day pill and
 * sliders/calendar/trash circles, horizontal tab pills (Screen Time / App Launches /
 * Browsing Time / Screen Unlocks / Usage Timeline), summary + hourly chart card,
 * Usage Overview with per-row ⋮ menu, per-app detail view (S15), session-length
 * breakdown (S18) and Usage Timeline with a dotted connector (S19).
 *
 * All times use the exact Stay Focused formats — [DurationFormat.formatHrsMins] for
 * totals, [DurationFormat.formatMinsSecs] for per-app/timeline durations,
 * [DurationFormat.formatLaunches] for launch counts — never `compact()` on these
 * screens. All numbers are real: [Graph.usage] (UsageStatsManager) for usage and
 * `focus_statistics` for the Protection section below.
 *
 * Honest limits (no data is invented):
 * - UsageStats only exposes real data for today, so stepping the day pill to a past
 *   day shows an empty state ("No usage data for …") instead of fake numbers.
 * - Yesterday's deltas (the red "+535 percent" / "+654 launches" on the Dashboard
 *   cards) are not shown here: no prior-day usage is stored.
 * - "Screen Unlocks" counts screen wake-ups (UsageStats SCREEN_INTERACTIVE), not
 *   true keyguard unlocks; the session breakdown counts only sessions of 3 minutes
 *   or more, matching the smallest bucket shown in S18.
 * - The trash button deletes only today's `focus_statistics` row (the Protection
 *   totals); usage stats themselves come from the system and cannot be cleared.
 */
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _binding: FragmentStatsBinding? = null
    private val b get() = _binding!!
    private val vm: StatsViewModel by viewModels()

    /** Headline range: 7 days (week total) or 30 days (month total). */
    private var selectedRangeDays = 7
    private var lastSummary: StatsSummary? = null

    /** Analytics tab (Stay Focused screenshots 3-4). */
    private var selectedTab = Tab.SCREEN_TIME
    private var lastUsage: UsageAnalytics? = null
    private val usageAdapter = UsageAdapter()
    private val timelineAdapter = TimelineAdapter()

    /** Day shown by the "< Today >" pill; usage data only exists for today. */
    private var selectedDate: LocalDate = LocalDate.now()

    /** Single-app detail view (S15): the package being shown, or null for the all-apps list. */
    private var detailPkg: String? = null
    private var detailLabel: String? = null
    private lateinit var backCallback: OnBackPressedCallback

    /** Filter-sheet state (FOUNDATION §15), persisted in SharedPreferences. */
    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val showSystemApps: Boolean get() = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
    private val sortOrder: String
        get() = prefs.getString(KEY_SORT, SORT_TIME) ?: SORT_TIME
    private val systemCache = HashMap<String, Boolean>()

    private enum class Tab { SCREEN_TIME, LAUNCHES, BROWSING, UNLOCKS, TIMELINE }

    companion object {
        const val ARG_TAB = "tab"
        const val ARG_PACKAGE = "package"

        private const val PREFS = "purelock_stats"
        private const val KEY_SHOW_SYSTEM = "show_system"
        private const val KEY_SORT = "sort"
        private const val KEY_HIDDEN = "hidden"
        private const val SORT_TIME = "time"
        private const val SORT_LAUNCHES = "launches"

        private const val MENU_DETAILS = 1
        private const val MENU_BLOCK = 2
        private const val MENU_HIDE = 3

        /** Deep link used by the Dashboard analytics cards (S01 "View"). */
        fun newInstance(tab: Tab, packageName: String? = null): StatsFragment =
            StatsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB, tab.name)
                    packageName?.let { putString(ARG_PACKAGE, it) }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStatsBinding.bind(view)
        UiAnim.cardEntrance(
            listOf(b.cardSummary, b.cardOverview, b.cardTimeline, b.cardSessions, b.cardTotals, b.cardChart, b.rowCounts, b.cardKeepGoing, b.cardAchievements)
        )

        arguments?.getString(ARG_TAB)?.let { name ->
            runCatching { Tab.valueOf(name) }.getOrNull()?.let { selectedTab = it }
        }
        arguments?.getString(ARG_PACKAGE)?.let { detailPkg = it }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitDetail()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        b.ivDayPrev.setOnClickListener {
            selectedDate = selectedDate.minusDays(1)
            renderUsageTab()
        }
        b.ivDayNext.setOnClickListener {
            val today = LocalDate.now()
            if (selectedDate < today) {
                selectedDate = selectedDate.plusDays(1)
                renderUsageTab()
            }
        }
        b.btnFilter.setOnClickListener { showFilterSheet() }
        b.btnCalendar.setOnClickListener { showDayPicker() }
        b.btnClear.setOnClickListener { showClearDialog() }
        b.btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        b.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.tvTabScreenTime.setOnClickListener { selectTab(Tab.SCREEN_TIME) }
        b.tvTabLaunches.setOnClickListener { selectTab(Tab.LAUNCHES) }
        b.tvTabBrowsing.setOnClickListener { selectTab(Tab.BROWSING) }
        b.tvTabUnlocks.setOnClickListener { selectTab(Tab.UNLOCKS) }
        b.tvTabTimeline.setOnClickListener { selectTab(Tab.TIMELINE) }

        b.barHourly.labelStep = 4
        b.barHourly.showValues = false
        b.listUsage.layoutManager = LinearLayoutManager(requireContext())
        b.listUsage.adapter = usageAdapter
        b.listTimeline.layoutManager = LinearLayoutManager(requireContext())
        b.listTimeline.adapter = timelineAdapter

        b.toggleRange.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            if (id == R.id.btnRangeCustom) {
                // No per-day data exists for a custom range, so it cannot be shown honestly.
                Toast.makeText(requireContext(), R.string.stats_range_custom_na, Toast.LENGTH_LONG).show()
                b.toggleRange.check(if (selectedRangeDays == 30) R.id.btnRange30 else R.id.btnRange7)
                return@addOnButtonCheckedListener
            }
            selectedRangeDays = if (id == R.id.btnRange30) 30 else 7
            lastSummary?.let { render(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.summary.collect { if (it != null) render(it) } }
                launch { vm.completedSessions.collect { b.tvSessions.text = it.toString() } }
                launch { vm.usage.collect { if (it != null) { lastUsage = it; renderUsageTab() } } }
            }
        }
        renderUsageTab()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshUsage()
    }

    // ------------------------------------------------------------------ header + tabs

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        renderUsageTab()
        // Keep the selected pill visible when the tab row is scrolled (S18/S19).
        val tabView = tabView(tab)
        b.tabScroll.post { b.tabScroll.smoothScrollTo((tabView.left - 16).coerceAtLeast(0), 0) }
    }

    private fun tabView(tab: Tab): TextView = when (tab) {
        Tab.SCREEN_TIME -> b.tvTabScreenTime
        Tab.LAUNCHES -> b.tvTabLaunches
        Tab.BROWSING -> b.tvTabBrowsing
        Tab.UNLOCKS -> b.tvTabUnlocks
        Tab.TIMELINE -> b.tvTabTimeline
    }

    /** In the single-app view (S15) the tab set is Screen Time / App Launches / Usage Timeline. */
    private fun updateTabs() {
        val inDetail = detailPkg != null
        for (tab in Tab.values()) {
            val view = tabView(tab)
            view.isVisible = !inDetail || tab == Tab.SCREEN_TIME || tab == Tab.LAUNCHES || tab == Tab.TIMELINE
            val selected = tab == selectedTab
            view.background = ContextCompat.getDrawable(
                requireContext(),
                if (selected) R.drawable.bg_sf_pill_primary else R.drawable.bg_sf_tab_white
            )
            view.setTextColor(
                if (selected) Color.WHITE
                else ContextCompat.getColor(requireContext(), R.color.sf_text)
            )
        }
    }

    private fun updateHeader() {
        val today = LocalDate.now()
        b.tvDayLabel.text = when {
            selectedDate == today -> getString(R.string.stats_today)
            selectedDate == today.minusDays(1) -> getString(R.string.sf_stats_yesterday)
            else -> selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        }
        b.ivDayNext.alpha = if (selectedDate < today) 1f else 0.35f
        // Timeline header (S19) has sliders + calendar + trash and NO day pill.
        b.dayPill.isVisible = selectedTab != Tab.TIMELINE
        b.btnCalendar.isVisible = selectedTab == Tab.TIMELINE
        // Single-app view (S15) has only the trash circle in the header.
        b.btnFilter.isVisible = detailPkg == null
    }

    // ------------------------------------------------------------------ analytics tabs

    private fun renderUsageTab() {
        if (_binding == null) return
        updateHeader()
        updateTabs()
        val usage = lastUsage ?: return
        val granted = usage.hasAccess
        val isToday = selectedDate == LocalDate.now()

        b.cardUsageGrant.isVisible = !granted
        if (!granted) {
            b.cardBrowsingEmpty.isVisible = false
            b.cardSummary.isVisible = false
            b.cardSessions.isVisible = false
            b.tvSectionTitle.isVisible = false
            b.cardOverview.isVisible = false
            b.cardTimeline.isVisible = false
            b.tvUsageEmpty.isVisible = false
            return
        }

        if (!isToday) {
            // UsageStats data is only available for today: show an honest empty state.
            b.cardBrowsingEmpty.isVisible = false
            b.cardSummary.isVisible = false
            b.cardSessions.isVisible = false
            b.tvSectionTitle.isVisible = false
            b.cardOverview.isVisible = false
            b.cardTimeline.isVisible = false
            b.tvUsageEmpty.isVisible = true
            b.tvUsageEmpty.text = getString(
                R.string.sf_stats_no_data_day,
                selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
            )
            return
        }

        b.tvUsageEmpty.isVisible = false
        val inDetail = detailPkg != null
        val launchesByPkg = usage.launches.associate {
            it.packageName to (it.detail.toIntOrNull() ?: 0)
        }

        when (selectedTab) {
            Tab.SCREEN_TIME -> {
                b.cardBrowsingEmpty.isVisible = false
                b.cardSessions.isVisible = false
                b.cardTimeline.isVisible = false
                b.cardSummary.isVisible = true
                b.tvSectionTitle.isVisible = !inDetail
                b.cardOverview.isVisible = !inDetail
                b.tvSectionTitle.setText(R.string.stats_usage_overview)
                if (inDetail) {
                    val pkg = detailPkg!!
                    setSummaryAppIcon(pkg)
                    val total = usage.perApp.firstOrNull { it.packageName == pkg }?.usageMs ?: 0L
                    b.tvSummaryValue.text = DurationFormat.formatMinsSecs(total)
                    b.tvSummaryLabel.setText(R.string.stats_total_screen_time)
                    b.barHourly.showValues = false
                    b.barHourly.valueFormatter = null
                    b.barHourly.dashedLineFraction = 0.75f
                    val hourly = hourlyMsForApp(pkg)
                    b.barHourly.yLabels = minuteLabels(hourly.maxOrNull() ?: 0L)
                    b.barHourly.setBars(hourly.mapIndexed { hour, ms -> BarChartView.Bar(hourLabel(hour), ms) })
                } else {
                    setSummaryIcon(R.drawable.ic_clock)
                    b.tvSummaryValue.text = DurationFormat.formatHrsMins(usage.totalMs)
                    b.tvSummaryLabel.setText(R.string.stats_total_screen_time)
                    b.barHourly.showValues = false
                    b.barHourly.valueFormatter = null
                    b.barHourly.dashedLineFraction = 0.75f
                    b.barHourly.yLabels = minuteLabels(usage.hourlyMs.maxOrNull() ?: 0L)
                    b.barHourly.setBars(
                        usage.hourlyMs.mapIndexed { hour, ms -> BarChartView.Bar(hourLabel(hour), ms) }
                    )
                    val rows = visibleRows(usage.perApp, UsageListMode.USAGE, launchesByPkg)
                    usageAdapter.submit(rows, UsageListMode.USAGE)
                    b.tvUsageEmpty.isVisible = rows.isEmpty()
                }
            }
            Tab.LAUNCHES -> {
                b.cardBrowsingEmpty.isVisible = false
                b.cardSessions.isVisible = false
                b.cardTimeline.isVisible = false
                b.cardSummary.isVisible = true
                b.tvSectionTitle.isVisible = !inDetail
                b.cardOverview.isVisible = !inDetail
                b.tvSectionTitle.setText(R.string.stats_tab_launches)
                val hourly: IntArray
                val totalLaunches: Int
                if (inDetail) {
                    val pkg = detailPkg!!
                    setSummaryAppIcon(pkg)
                    totalLaunches = launchesByPkg[pkg] ?: 0
                    hourly = hourlyCountForApp(pkg)
                } else {
                    setSummaryIcon(R.drawable.ic_sf_rocket)
                    totalLaunches = usage.launches.sumOf { it.detail.toIntOrNull() ?: 0 }
                    hourly = usage.hourlyLaunches
                }
                b.tvSummaryValue.text = totalLaunches.toString()
                b.tvSummaryLabel.setText(R.string.sf_stats_app_launches)
                b.barHourly.showValues = false
                b.barHourly.valueFormatter = null
                b.barHourly.dashedLineFraction = 0.67f
                val maxCount = hourly.maxOrNull() ?: 0
                b.barHourly.yLabels = countLabels(maxCount)
                b.barHourly.setBars(
                    hourly.mapIndexed { hour, n -> BarChartView.Bar(hourLabel(hour), n.toLong()) }
                )
                if (!inDetail) {
                    val rows = visibleRows(usage.launches, UsageListMode.LAUNCHES, launchesByPkg)
                    usageAdapter.submit(rows, UsageListMode.LAUNCHES)
                    b.tvUsageEmpty.isVisible = rows.isEmpty()
                }
            }
            Tab.BROWSING -> {
                // S17: without accessibility permission this tab is an empty state.
                val accessibilityOn = Graph.integrity.isAccessibilityEnabled()
                b.cardBrowsingEmpty.isVisible = !accessibilityOn
                b.cardSessions.isVisible = false
                b.cardTimeline.isVisible = false
                b.cardSummary.isVisible = accessibilityOn
                b.tvSectionTitle.isVisible = accessibilityOn
                b.cardOverview.isVisible = accessibilityOn
                if (!accessibilityOn) return
                b.tvSectionTitle.setText(R.string.stats_tab_browsing)
                setSummaryIcon(R.drawable.ic_globe)
                b.tvSummaryValue.text = DurationFormat.formatHrsMins(usage.browsingMs)
                b.tvSummaryLabel.setText(R.string.stats_browsing_total)
                b.barHourly.showValues = false
                b.barHourly.valueFormatter = null
                b.barHourly.dashedLineFraction = 0.75f
                val hourly = hourlyBrowsingMs()
                b.barHourly.yLabels = minuteLabels(hourly.maxOrNull() ?: 0L)
                b.barHourly.setBars(
                    hourly.mapIndexed { hour, ms -> BarChartView.Bar(hourLabel(hour), ms) }
                )
                val rows = visibleRows(usage.browsingPerApp, UsageListMode.USAGE, launchesByPkg)
                usageAdapter.submit(rows, UsageListMode.USAGE)
                b.tvUsageEmpty.isVisible = rows.isEmpty()
            }
            Tab.UNLOCKS -> {
                b.cardBrowsingEmpty.isVisible = false
                b.cardTimeline.isVisible = false
                b.tvSectionTitle.isVisible = false
                b.cardOverview.isVisible = false
                b.cardSummary.isVisible = true
                b.cardSessions.isVisible = true
                setSummaryIcon(R.drawable.ic_sf_monitor_lock)
                b.tvSummaryValue.text = usage.unlocks.toString()
                b.tvSummaryLabel.setText(R.string.sf_stats_phone_unlocks)
                // Each bar is labelled with its count on top (S18).
                b.barHourly.showValues = true
                b.barHourly.valueFormatter = { it.toString() }
                b.barHourly.dashedLineFraction = null
                val maxCount = usage.hourlyUnlocks.maxOrNull() ?: 0
                b.barHourly.yLabels = unlockLabels(maxCount)
                b.barHourly.setBars(
                    usage.hourlyUnlocks.mapIndexed { hour, n -> BarChartView.Bar(hourLabel(hour), n.toLong()) }
                )
                renderSessionBreakdown(usage.timeline)
            }
            Tab.TIMELINE -> {
                b.cardBrowsingEmpty.isVisible = false
                b.cardSummary.isVisible = false
                b.cardSessions.isVisible = false
                b.tvSectionTitle.isVisible = false
                b.cardOverview.isVisible = false
                b.cardTimeline.isVisible = true
                b.tvTimelineDate.text = selectedDate.format(
                    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                )
                val rows = if (inDetail) {
                    usage.timeline.filter { it.packageName == detailPkg }
                } else {
                    usage.timeline
                }
                timelineAdapter.submit(rows)
                b.tvUsageEmpty.isVisible = rows.isEmpty()
            }
        }
    }

    /** 0 -> "12 AM", 4 -> "04 AM", 12 -> "12 PM", 16 -> "04 PM". */
    private fun hourLabel(hour: Int): String {
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val ampm = if (hour < 12) "AM" else "PM"
        return "%02d %s".format(hour12, ampm)
    }

    /** Y-axis labels for a time chart: 0m, 1/3, 2/3, max (e.g. 0m/19m/39m/59m). */
    private fun minuteLabels(maxMs: Long): List<String> {
        val maxMin = ((maxMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
        return (0..3).map { "${maxMin * it / 3}m" }
    }

    /** Y-axis labels for a count chart: 0, 1/3, 2/3, max (e.g. 0/100/200/300). */
    private fun countLabels(maxCount: Int): List<String> =
        (0..3).map { "${maxCount * it / 3}" }

    /** Y-axis labels for the unlocks chart (0..max, e.g. 0/1). */
    private fun unlockLabels(maxCount: Int): List<String> =
        if (maxCount <= 3) (0..maxCount).map { "$it" } else countLabels(maxCount)

    private fun setSummaryIcon(resId: Int) {
        b.ivSummaryIcon.setImageResource(resId)
        ImageViewCompat.setImageTintList(b.ivSummaryIcon, ColorStateList.valueOf(Color.WHITE))
    }

    /** Single-app view (S15): the app's own icon inside the gradient circle. */
    private fun setSummaryAppIcon(pkg: String) {
        val icon = runCatching { requireContext().packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (icon != null) {
            b.ivSummaryIcon.setImageDrawable(icon)
            ImageViewCompat.setImageTintList(b.ivSummaryIcon, null)
        } else {
            setSummaryIcon(R.drawable.ic_nav_shield)
        }
    }

    /** Per-app hourly foreground time, bucketed by session start hour (from today's timeline). */
    private fun hourlyMsForApp(pkg: String): LongArray {
        val out = LongArray(24)
        val usage = lastUsage ?: return out
        val dayStart = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (row in usage.timeline) {
            if (row.packageName != pkg) continue
            val hour = ((row.timestampMs - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            out[hour] += row.durationMs
        }
        return out
    }

    /** Per-app foreground opens per hour (from today's timeline). */
    private fun hourlyCountForApp(pkg: String): IntArray {
        val out = IntArray(24)
        val usage = lastUsage ?: return out
        val dayStart = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (row in usage.timeline) {
            if (row.packageName != pkg) continue
            val hour = ((row.timestampMs - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            out[hour]++
        }
        return out
    }

    /** Hourly browsing time, bucketed by session start hour (browser apps only, from today's timeline). */
    private fun hourlyBrowsingMs(): LongArray {
        val out = LongArray(24)
        val usage = lastUsage ?: return out
        val dayStart = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (row in usage.timeline) {
            if (row.packageName !in UsageRepository.BROWSER_PACKAGES) continue
            val hour = ((row.timestampMs - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            out[hour] += row.durationMs
        }
        return out
    }

    /** S18 session-length breakdown, from today's foreground sessions. */
    private fun renderSessionBreakdown(timeline: List<TimelineRow>) {
        // Buckets (top -> bottom) exactly as in S18; sessions under 3 minutes fall in
        // no bucket — the screenshot's smallest bucket is "3-5 min".
        val labels = listOf(
            getString(R.string.sf_stats_bucket_over, 60),
            getString(R.string.sf_stats_bucket_range, 45, 60),
            getString(R.string.sf_stats_bucket_range, 30, 45),
            getString(R.string.sf_stats_bucket_range, 20, 30),
            getString(R.string.sf_stats_bucket_range, 15, 20),
            getString(R.string.sf_stats_bucket_range, 10, 15),
            getString(R.string.sf_stats_bucket_range, 5, 10),
            getString(R.string.sf_stats_bucket_range, 3, 5)
        )
        val counts = IntArray(labels.size)
        for (row in timeline) {
            val mins = row.durationMs / 60_000.0
            val bucket = when {
                mins > 60 -> 0
                mins >= 45 -> 1
                mins >= 30 -> 2
                mins >= 20 -> 3
                mins >= 15 -> 4
                mins >= 10 -> 5
                mins >= 5 -> 6
                mins >= 3 -> 7
                else -> -1
            }
            if (bucket >= 0) counts[bucket]++
        }
        val max = counts.maxOrNull() ?: 0
        b.listSessions.removeAllViews()
        for (i in labels.indices) {
            val vb = ItemSfSessionRowBinding.inflate(layoutInflater, b.listSessions, false)
            vb.tvSfSessionLabel.text = labels[i]
            vb.tvSfSessionCount.text = counts[i].toString()
            vb.pbSfSessionBar.progress = if (max > 0) (counts[i] * 100 / max) else 0
            b.listSessions.addView(vb.root)
        }
    }

    // ------------------------------------------------------------------ filter sheet, clear, day picker

    /** Analytics filter sheet (FOUNDATION §15): Show system apps + sort order. */
    private fun showFilterSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sb = SheetSfStatsFilterBinding.inflate(layoutInflater)
        dialog.setContentView(sb.root)
        sb.switchShowSystem.isChecked = showSystemApps
        sb.switchShowSystem.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_SYSTEM, checked).apply()
            renderUsageTab()
        }
        when (sortOrder) {
            SORT_LAUNCHES -> sb.rgSort.check(R.id.radioSortLaunches)
            else -> sb.rgSort.check(R.id.radioSortTime)
        }
        sb.rgSort.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(
                KEY_SORT, if (id == R.id.radioSortLaunches) SORT_LAUNCHES else SORT_TIME
            ).apply()
            renderUsageTab()
        }
        val hidden = hiddenPkgs()
        sb.btnResetHidden.isVisible = hidden.isNotEmpty()
        sb.btnResetHidden.setOnClickListener {
            prefs.edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
            sb.btnResetHidden.isVisible = false
            renderUsageTab()
        }
        dialog.show()
    }

    /** Trash (FOUNDATION §15): confirm dialog; deletes today's focus_statistics rows only. */
    private fun showClearDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sf_stats_clear_title)
            .setMessage(R.string.sf_stats_clear_message)
            .setPositiveButton(R.string.sf_stats_clear) { _, _ -> clearTodayStats() }
            .setNegativeButton(R.string.sf_stats_cancel, null)
            .show()
    }

    private fun clearTodayStats() {
        // StatsDao.deleteByDateKey: deletes only today's row (usage stats
        // themselves cannot be cleared — the dialog already says so).
        viewLifecycleOwner.lifecycleScope.launch {
            val cleared = runCatching {
                withContext(Dispatchers.IO) {
                    Graph.db.statsDao().deleteByDateKey(DayUtils.dateKey(System.currentTimeMillis()))
                }
                true
            }.getOrDefault(false)
            if (cleared) {
                Toast.makeText(requireContext(), R.string.sf_stats_cleared, Toast.LENGTH_SHORT).show()
                vm.refreshUsage()
            }
        }
    }

    /** Timeline calendar (S19): pick the day shown; future days are not allowed. */
    private fun showDayPicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                renderUsageTab()
            },
            selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    // ------------------------------------------------------------------ per-app detail + row actions

    private fun openDetail(row: UsageAppRow) {
        detailPkg = row.packageName
        detailLabel = row.label
        // The single-app view (S15) only has Screen Time / App Launches / Usage Timeline.
        if (selectedTab == Tab.BROWSING || selectedTab == Tab.UNLOCKS) selectedTab = Tab.SCREEN_TIME
        backCallback.isEnabled = true
        renderUsageTab()
    }

    private fun exitDetail() {
        detailPkg = null
        detailLabel = null
        backCallback.isEnabled = false
        renderUsageTab()
    }

    private fun hiddenPkgs(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    private fun isSystemApp(pkg: String): Boolean = systemCache.getOrPut(pkg) {
        runCatching {
            val info = requireContext().packageManager.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
    }

    /** Applies the filter sheet (hidden apps, system apps) and the sort order to a list. */
    private fun visibleRows(
        rows: List<UsageAppRow>,
        mode: UsageListMode,
        launchesByPkg: Map<String, Int>
    ): List<UsageAppRow> {
        val hidden = hiddenPkgs()
        var out = rows.filter { it.packageName !in hidden }
        if (!showSystemApps) out = out.filter { !isSystemApp(it.packageName) }
        if (mode == UsageListMode.USAGE && sortOrder == SORT_LAUNCHES) {
            out = out.sortedByDescending { launchesByPkg[it.packageName] ?: 0 }
        }
        return out
    }

    /** Usage Overview ⋮ menu (FOUNDATION §9): View details, Block this app, Hide app. */
    private fun showRowMenu(anchor: View, row: UsageAppRow) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(Menu.NONE, MENU_DETAILS, Menu.NONE, R.string.sf_stats_menu_details)
        menu.menu.add(Menu.NONE, MENU_BLOCK, Menu.NONE, R.string.sf_stats_menu_block)
        menu.menu.add(Menu.NONE, MENU_HIDE, Menu.NONE, R.string.sf_stats_menu_hide)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_DETAILS -> openDetail(row)
                MENU_BLOCK -> startActivity(
                    Intent(requireContext(), AppListActivity::class.java)
                        .putExtra(AppListActivity.EXTRA_PACKAGE, row.packageName)
                )
                MENU_HIDE -> {
                    val hidden = hiddenPkgs().toMutableSet()
                    hidden.add(row.packageName)
                    prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply()
                    Toast.makeText(requireContext(), R.string.sf_stats_hidden, Toast.LENGTH_SHORT).show()
                    renderUsageTab()
                }
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------ protection stats (unchanged)

    private fun render(s: StatsSummary) {
        lastSummary = s
        b.tvEmpty.isVisible = s.totalMs == 0L && s.websiteBlocks == 0 && s.appBlocks == 0
        // Headline follows the selected range (real totals only; no prior-period
        // data exists, so the mockup's "+12%" delta is deliberately not shown).
        b.tvTotal.text = DurationFormat.formatHrsMins(if (selectedRangeDays == 30) s.monthMs else s.weekMs)
        b.tvToday.text = DurationFormat.formatHrsMins(s.todayMs)
        b.tvWeek.text = DurationFormat.formatHrsMins(s.weekMs)
        b.tvMonth.text = DurationFormat.formatHrsMins(s.monthMs)
        b.tvStreak.text = resources.getQuantityString(R.plurals.days_count, s.streakDays, s.streakDays)
        b.tvWebBlocks.text = s.websiteBlocks.toString()
        b.tvAppBlocks.text = s.appBlocks.toString()
        b.tvInterruptions.text = s.interruptions.toString()

        val locale = Locale.getDefault()
        b.chart.setPoints(
            s.last7Days.map { (date, ms) ->
                LineChartView.Point(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), ms)
            }
        )

        val unlocked = StatsCalculator.unlocked(s)
        b.tvAchCount.text = unlocked.size.toString()
        b.listAchievements.removeAllViews()
        for (achievement in Achievement.values()) {
            val done = achievement in unlocked
            val row = TextView(requireContext()).apply {
                text = (if (done) "✓  " else "○  ") + getString(titleOf(achievement))
                textSize = 15f
                setPadding(0, 8, 0, 8)
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, if (done) R.attr.plSuccess else R.attr.plTextSecondary, 0
                    )
                )
            }
            b.listAchievements.addView(row)
        }
    }

    private fun titleOf(a: Achievement): Int = when (a) {
        Achievement.FIRST_LOCK -> R.string.ach_first
        Achievement.STREAK_3 -> R.string.ach_streak3
        Achievement.STREAK_7 -> R.string.ach_streak7
        Achievement.STREAK_30 -> R.string.ach_streak30
        Achievement.TOTAL_24H -> R.string.ach_total24
        Achievement.BLOCKS_100 -> R.string.ach_blocks100
    }

    // ------------------------------------------------------------------ adapters

    private inner class UsageAdapter : RecyclerView.Adapter<UsageAdapter.VH>() {
        private var rows: List<UsageAppRow> = emptyList()
        private var mode: UsageListMode = UsageListMode.USAGE

        fun submit(rows: List<UsageAppRow>, mode: UsageListMode) {
            this.rows = rows
            this.mode = mode
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSfUsageRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val vb = holder.vb
            vb.ivSfIcon.setImageDrawable(
                runCatching { holder.itemView.context.packageManager.getApplicationIcon(row.packageName) }
                    .getOrNull() ?: holder.itemView.context.getDrawable(R.drawable.ic_nav_shield)
            )
            vb.tvSfName.text = row.label
            if (mode == UsageListMode.LAUNCHES) {
                val count = row.detail.toIntOrNull() ?: 0
                vb.tvSfValue.text = DurationFormat.formatLaunches(count)
                val max = rows.maxOfOrNull { it.detail.toIntOrNull() ?: 0 } ?: 0
                vb.pbSfUsage.isVisible = max > 0
                vb.pbSfUsage.progress = if (max > 0) (count * 100 / max) else 0
            } else {
                vb.tvSfValue.text = DurationFormat.formatMinsSecs(row.usageMs)
                val max = rows.maxOfOrNull { it.usageMs } ?: 0L
                vb.pbSfUsage.isVisible = max > 0
                vb.pbSfUsage.progress = if (max > 0) ((row.usageMs * 100 / max).toInt()) else 0
            }
            vb.vSfDivider.isVisible = position < itemCount - 1
            vb.btnSfMenu.setOnClickListener { showRowMenu(vb.btnSfMenu, row) }
            holder.itemView.setOnClickListener { openDetail(row) }
        }

        inner class VH(val vb: ItemSfUsageRowBinding) : RecyclerView.ViewHolder(vb.root)
    }

    private inner class TimelineAdapter : RecyclerView.Adapter<TimelineAdapter.VH>() {
        private var rows: List<TimelineRow> = emptyList()

        fun submit(rows: List<TimelineRow>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemUsageTimelineBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val vb = holder.vb
            vb.ivTimelineIcon.setImageDrawable(
                runCatching { holder.itemView.context.packageManager.getApplicationIcon(row.packageName) }
                    .getOrNull() ?: holder.itemView.context.getDrawable(R.drawable.ic_nav_shield)
            )
            vb.tvTimelineLabel.text = row.label
            val time = Instant.ofEpochMilli(row.timestampMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
            vb.tvTimelineTime.text = time
            vb.tvTimelineDuration.text = DurationFormat.formatMinsSecs(row.durationMs)
        }

        inner class VH(val vb: ItemUsageTimelineBinding) : RecyclerView.ViewHolder(vb.root)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

/** What a usage-list row shows: a usage duration (with bar) or a launch count. */
private enum class UsageListMode { USAGE, LAUNCHES }
