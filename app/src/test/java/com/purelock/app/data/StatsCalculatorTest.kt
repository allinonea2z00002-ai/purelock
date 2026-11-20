package com.purelock.app.data

import com.purelock.app.data.db.FocusStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsCalculatorTest {

    private val today = LocalDate.of(2026, 11, 19)
    private val hour = 3_600_000L

    private fun row(date: String, ms: Long, web: Int = 0, apps: Int = 0, interruptions: Int = 0) =
        FocusStatistics(dateKey = date, protectedMs = ms, websiteBlocks = web, appBlocks = apps, interruptions = interruptions)

    @Test
    fun `empty data gives zeros and not invented numbers`() {
        val s = StatsCalculator.summarize(emptyList(), today)
        assertEquals(0L, s.totalMs)
        assertEquals(0L, s.todayMs)
        assertEquals(0, s.streakDays)
        assertEquals(0, s.websiteBlocks)
        assertEquals(7, s.last7Days.size)
        assertTrue(s.last7Days.all { it.second == 0L })
    }

    @Test
    fun `totals windows and counts add up`() {
        val rows = listOf(
            row("2026-11-19", hour, web = 2, apps = 1),
            row("2026-11-18", 2 * hour, web = 1, interruptions = 1),
            row("2026-11-17", 0),
            row("2026-11-15", hour / 2),
            row("2026-10-01", hour)                       // outside the 30-day window
        )
        val s = StatsCalculator.summarize(rows, today)
        assertEquals(hour + 2 * hour + hour / 2 + hour, s.totalMs)
        assertEquals(hour, s.todayMs)
        assertEquals(hour + 2 * hour + hour / 2, s.weekMs)
        assertEquals(hour + 2 * hour + hour / 2, s.monthMs)
        assertEquals(3, s.websiteBlocks)
        assertEquals(1, s.appBlocks)
        assertEquals(1, s.interruptions)
    }

    @Test
    fun `last seven days end today and are in order`() {
        val s = StatsCalculator.summarize(listOf(row("2026-11-19", hour)), today)
        assertEquals(LocalDate.of(2026, 11, 13), s.last7Days.first().first)
        assertEquals(today, s.last7Days.last().first)
        assertEquals(hour, s.last7Days.last().second)
    }

    @Test
    fun `streak counts consecutive protected days`() {
        val rows = listOf(row("2026-11-19", hour), row("2026-11-18", hour), row("2026-11-17", 0), row("2026-11-16", hour))
        assertEquals(2, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `an empty today does not break the streak yet`() {
        val rows = listOf(row("2026-11-18", hour), row("2026-11-17", hour))
        assertEquals(2, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `a missed day resets the streak`() {
        val rows = listOf(row("2026-11-17", hour), row("2026-11-16", hour))
        assertEquals(0, StatsCalculator.summarize(rows, today).streakDays)
    }

    @Test
    fun `malformed date keys are ignored instead of crashing`() {
        val s = StatsCalculator.summarize(listOf(row("garbage", hour), row("2026-11-19", hour)), today)
        assertEquals(hour, s.todayMs)
    }

    @Test
    fun `achievements unlock from real numbers`() {
        fun summary(total: Long, streak: Int, blocks: Int) = StatsSummary(
            totalMs = total, todayMs = 0, weekMs = 0, monthMs = 0, streakDays = streak,
            websiteBlocks = blocks, appBlocks = 0, interruptions = 0, last7Days = emptyList()
        )
        assertTrue(StatsCalculator.unlocked(summary(0, 0, 0)).isEmpty())

        val first = StatsCalculator.unlocked(summary(1, 0, 0))
        assertEquals(setOf(Achievement.FIRST_LOCK), first)

        val big = StatsCalculator.unlocked(summary(24 * hour, 7, 100))
        assertTrue(Achievement.STREAK_3 in big)
        assertTrue(Achievement.STREAK_7 in big)
        assertFalse(Achievement.STREAK_30 in big)
        assertTrue(Achievement.TOTAL_24H in big)
        assertTrue(Achievement.BLOCKS_100 in big)
    }
}
