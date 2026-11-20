package com.purelock.app.ui.stats

import android.os.Bundle
import android.view.View
import android.widget.TextView
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
import com.purelock.app.ui.widget.BarChartView
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _binding: FragmentStatsBinding? = null
    private val b get() = _binding!!
    private val vm: StatsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentStatsBinding.bind(view)
        UiAnim.cardEntrance(listOf(b.cardTotals, b.cardChart, b.rowCounts, b.cardBlocks, b.cardAchievements))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.summary.collect { if (it != null) render(it) } }
                launch { vm.completedSessions.collect { b.tvSessions.text = it.toString() } }
            }
        }
    }

    private fun render(s: StatsSummary) {
        b.tvEmpty.isVisible = s.totalMs == 0L && s.websiteBlocks == 0 && s.appBlocks == 0
        b.tvTotal.text = DurationFormat.compact(s.totalMs)
        b.tvToday.text = DurationFormat.compact(s.todayMs)
        b.tvWeek.text = DurationFormat.compact(s.weekMs)
        b.tvMonth.text = DurationFormat.compact(s.monthMs)
        b.tvStreak.text = resources.getQuantityString(R.plurals.days_count, s.streakDays, s.streakDays)
        b.tvWebBlocks.text = getString(R.string.stats_web_blocks, s.websiteBlocks)
        b.tvAppBlocks.text = getString(R.string.stats_app_blocks, s.appBlocks)
        b.tvInterruptions.text = getString(R.string.stats_interruptions, s.interruptions)

        val locale = Locale.getDefault()
        b.chart.setBars(
            s.last7Days.map { (date, ms) ->
                BarChartView.Bar(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), ms)
            }
        )

        val unlocked = StatsCalculator.unlocked(s)
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
