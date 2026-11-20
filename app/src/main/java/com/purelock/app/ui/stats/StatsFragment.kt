package com.purelock.app.ui.stats

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.R
import com.purelock.app.data.Achievement
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.StatsSummary
import com.purelock.app.databinding.FragmentStatsBinding
import com.purelock.app.databinding.ItemAppBinding
import com.purelock.app.databinding.ItemUsageTimelineBinding
import com.purelock.app.ui.UiAnim
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.widget.BarChartView
import com.purelock.app.ui.widget.LineChartView
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    private enum class Tab { SCREEN_TIME, LAUNCHES, BROWSING, UNLOCKS, TIMELINE }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStatsBinding.bind(view)
        UiAnim.cardEntrance(
            listOf(b.cardScreenTotal, b.cardHourly, b.cardTotals, b.cardChart, b.rowCounts, b.cardKeepGoing, b.cardAchievements)
        )
        b.btnBackStats.setOnClickListener { (requireActivity() as MainActivity).openTab(R.id.nav_home) }
        val today = LocalDate.now()
        b.tvTodayDate.text = getString(R.string.stats_today) + " · " +
            today.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        b.tvTimelineDate.text = today.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))

        b.toggleUsageTabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedTab = when (id) {
                R.id.btnTabLaunches -> Tab.LAUNCHES
                R.id.btnTabBrowsing -> Tab.BROWSING
                R.id.btnTabUnlocks -> Tab.UNLOCKS
                R.id.btnTabTimeline -> Tab.TIMELINE
                else -> Tab.SCREEN_TIME
            }
            renderUsageTab()
        }
        b.btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
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
    }

    override fun onResume() {
        super.onResume()
        vm.refreshUsage()
    }

    // ------------------------------------------------------------------ analytics tabs

    private fun renderUsageTab() {
        val usage = lastUsage ?: return
        val granted = usage.hasAccess
        b.cardUsageGrant.isVisible = !granted
        val showContent = granted
        b.cardScreenTotal.isVisible = showContent && selectedTab == Tab.SCREEN_TIME
        b.cardHourly.isVisible = showContent && selectedTab == Tab.SCREEN_TIME
        b.cardBrowsingTotal.isVisible = showContent && selectedTab == Tab.BROWSING
        b.cardUnlocks.isVisible = showContent && selectedTab == Tab.UNLOCKS
        b.tvSectionTitle.isVisible = showContent && selectedTab != Tab.UNLOCKS
        b.tvTimelineDate.isVisible = showContent && selectedTab == Tab.TIMELINE
        b.listUsage.isVisible = showContent && selectedTab != Tab.TIMELINE && selectedTab != Tab.UNLOCKS
        b.listTimeline.isVisible = showContent && selectedTab == Tab.TIMELINE
        b.tvUsageEmpty.isVisible = false
        if (!granted) return

        when (selectedTab) {
            Tab.SCREEN_TIME -> {
                b.tvScreenTotal.text = DurationFormat.compact(usage.totalMs)
                b.barHourly.setBars(
                    usage.hourlyMs.mapIndexed { hour, ms -> BarChartView.Bar(hourLabel(hour), ms) }
                )
                b.tvSectionTitle.setText(R.string.stats_usage_overview)
                usageAdapter.submit(usage.perApp, UsageListMode.USAGE)
                b.tvUsageEmpty.isVisible = usage.perApp.isEmpty()
            }
            Tab.LAUNCHES -> {
                b.tvSectionTitle.setText(R.string.stats_tab_launches)
                usageAdapter.submit(usage.launches, UsageListMode.LAUNCHES)
                b.tvUsageEmpty.isVisible = usage.launches.isEmpty()
            }
            Tab.BROWSING -> {
                b.tvBrowsingTotal.text = DurationFormat.compact(usage.browsingMs)
                b.tvSectionTitle.setText(R.string.stats_tab_browsing)
                usageAdapter.submit(usage.browsingPerApp, UsageListMode.USAGE)
                b.tvUsageEmpty.isVisible = usage.browsingPerApp.isEmpty()
            }
            Tab.UNLOCKS -> {
                b.tvUnlockCount.text = usage.unlocks.toString()
            }
            Tab.TIMELINE -> {
                b.tvSectionTitle.setText(R.string.stats_tab_timeline)
                timelineAdapter.submit(usage.timeline)
                b.tvUsageEmpty.isVisible = usage.timeline.isEmpty()
            }
        }
    }

    /** 0 -> "12 AM", 4 -> "04 AM", 12 -> "12 PM", 16 -> "04 PM". */
    private fun hourLabel(hour: Int): String {
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val ampm = if (hour < 12) "AM" else "PM"
        return "%02d %s".format(hour12, ampm)
    }

    // ------------------------------------------------------------------ protection stats (unchanged)

    private fun render(s: StatsSummary) {
        lastSummary = s
        b.tvEmpty.isVisible = s.totalMs == 0L && s.websiteBlocks == 0 && s.appBlocks == 0
        // Headline follows the selected range (real totals only; no prior-period
        // data exists, so the mockup's "+12%" delta is deliberately not shown).
        b.tvTotal.text = DurationFormat.compact(if (selectedRangeDays == 30) s.monthMs else s.weekMs)
        b.tvToday.text = DurationFormat.compact(s.todayMs)
        b.tvWeek.text = DurationFormat.compact(s.weekMs)
        b.tvMonth.text = DurationFormat.compact(s.monthMs)
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
            VH(ItemAppBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val vb = holder.vb
            vb.ivIcon.setImageDrawable(
                runCatching { holder.itemView.context.packageManager.getApplicationIcon(row.packageName) }
                    .getOrNull() ?: holder.itemView.context.getDrawable(R.drawable.ic_nav_shield)
            )
            vb.tvLabel.text = row.label
            vb.tvDetail.text = row.packageName
            if (mode == UsageListMode.LAUNCHES) {
                vb.tvState.text = holder.itemView.context.getString(R.string.stats_launches_value, row.detail.toIntOrNull() ?: 0)
                vb.pbUsage.isVisible = false
            } else {
                vb.tvState.text = DurationFormat.compact(row.usageMs)
                val max = rows.maxOfOrNull { it.usageMs } ?: 0L
                vb.pbUsage.isVisible = max > 0
                vb.pbUsage.progress = if (max > 0) ((row.usageMs * 100 / max).toInt()) else 0
            }
        }

        inner class VH(val vb: ItemAppBinding) : RecyclerView.ViewHolder(vb.root)
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
            vb.tvTimelineDuration.text = DurationFormat.compact(row.durationMs)
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
