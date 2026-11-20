package com.purelock.app.data

import com.purelock.app.data.db.FocusStatistics
import java.time.LocalDate

data class StatsSummary(
    val totalMs: Long,
    val todayMs: Long,
    val weekMs: Long,
    val monthMs: Long,
    val streakDays: Int,
    val websiteBlocks: Int,
    val appBlocks: Int,
    val interruptions: Int,
    /** Oldest -> newest, always 7 entries ending today. */
    val last7Days: List<Pair<LocalDate, Long>>
)

enum class Achievement { FIRST_LOCK, STREAK_3, STREAK_7, STREAK_30, TOTAL_24H, BLOCKS_100 }

/** Pure computation over stored rows, so it can be unit-tested. */
object StatsCalculator {

    fun summarize(rows: List<FocusStatistics>, today: LocalDate): StatsSummary {
        val byDate: Map<LocalDate, FocusStatistics> =
            rows.mapNotNull { r -> runCatching { LocalDate.parse(r.dateKey) to r }.getOrNull() }.toMap()

        fun sumLastDays(days: Long): Long =
            (0 until days).sumOf { byDate[today.minusDays(it)]?.protectedMs ?: 0L }

        val last7 = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            d to (byDate[d]?.protectedMs ?: 0L)
        }

        return StatsSummary(
            totalMs = rows.sumOf { it.protectedMs },
            todayMs = byDate[today]?.protectedMs ?: 0L,
            weekMs = sumLastDays(7),
            monthMs = sumLastDays(30),
            streakDays = streak(byDate, today),
            websiteBlocks = rows.sumOf { it.websiteBlocks },
            appBlocks = rows.sumOf { it.appBlocks },
            interruptions = rows.sumOf { it.interruptions },
            last7Days = last7
        )
    }

    /** Consecutive protected days. Today does not break the streak until the day is over. */
    fun streak(byDate: Map<LocalDate, FocusStatistics>, today: LocalDate): Int {
        var day = if ((byDate[today]?.protectedMs ?: 0L) > 0) today else today.minusDays(1)
        var count = 0
        while ((byDate[day]?.protectedMs ?: 0L) > 0) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    fun unlocked(summary: StatsSummary): Set<Achievement> {
        val result = HashSet<Achievement>()
        if (summary.totalMs > 0) result += Achievement.FIRST_LOCK
        if (summary.streakDays >= 3) result += Achievement.STREAK_3
        if (summary.streakDays >= 7) result += Achievement.STREAK_7
        if (summary.streakDays >= 30) result += Achievement.STREAK_30
        if (summary.totalMs >= 24L * 3_600_000L) result += Achievement.TOTAL_24H
        if (summary.websiteBlocks + summary.appBlocks >= 100) result += Achievement.BLOCKS_100
        return result
    }
}
