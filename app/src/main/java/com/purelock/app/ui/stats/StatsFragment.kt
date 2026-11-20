package com.purelock.app.ui.stats

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.purelock.app.R
import com.purelock.app.data.Achievement
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.StatsSummary
import com.purelock.app.databinding.FragmentStatsBinding
import com.purelock.app.ui.UiAnim
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.widget.LineChartView
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _binding: FragmentStatsBinding? = null
    private val b get() = _binding!!
    private val vm: StatsViewModel by viewModels()

    /** Headline range: 7 days (week total) or 30 days (month total). */
    private var selectedRangeDays = 7
    private var lastSummary: StatsSummary? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStatsBinding.bind(view)
        UiAnim.cardEntrance(listOf(b.cardTotals, b.cardChart, b.rowCounts, b.cardKeepGoing, b.cardAchievements))
        b.btnBackStats.setOnClickListener { (requireActivity() as MainActivity).openTab(R.id.nav_home) }
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
            }
        }
    }

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
                LineChartView.Point(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), ms)
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
