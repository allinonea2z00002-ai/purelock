package com.purelock.app.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class FormatAndDayTest {

    @Before
    fun fixLocale() {
        Locale.setDefault(Locale.US) // so digits and decimal separators are predictable
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ------------------------------------------------------------------ DurationFormat

    @Test
    fun `clock formatting`() {
        assertEquals("1d 02:03:04", DurationFormat.clock(93_784_000))
        assertEquals("01:02:05", DurationFormat.clock(3_725_000))
        assertEquals("00:59", DurationFormat.clock(59_000))
        assertEquals("00:00", DurationFormat.clock(0))
        assertEquals("00:00", DurationFormat.clock(-5))
        assertEquals("00:01", DurationFormat.clock(1)) // never shows 00:00 before the real end
    }

    @Test
    fun `compact formatting`() {
        assertEquals("3h 20m", DurationFormat.compact(12_000_000))
        assertEquals("45m", DurationFormat.compact(45 * 60_000L))
        assertEquals("2d 4h", DurationFormat.compact((2 * 1_440 + 240) * 60_000L))
        assertEquals("0m", DurationFormat.compact(-1))
    }

    @Test
    fun `distance formatting`() {
        assertEquals("500 m", DurationFormat.distance(500f))
        assertEquals("999 m", DurationFormat.distance(999.9f))
        assertEquals("1.50 km", DurationFormat.distance(1_500f))
    }

    // ------------------------------------------------------------------ DayUtils

    @Test
    fun `date key follows the given time zone`() {
        val t = ms("2026-11-19T20:00:00Z")
        assertEquals("2026-11-19", DayUtils.dateKey(t, ZoneId.of("UTC")))
        assertEquals("2026-11-20", DayUtils.dateKey(t, ZoneId.of("Asia/Dhaka")))
    }

    @Test
    fun `an interval inside one day is not split`() {
        val utc = ZoneId.of("UTC")
        val parts = DayUtils.splitByDay(ms("2026-11-19T10:00:00Z"), ms("2026-11-19T12:00:00Z"), utc)
        assertEquals(listOf("2026-11-19" to 2 * 3_600_000L), parts)
    }

    @Test
    fun `an interval across midnight is split between both days`() {
        val utc = ZoneId.of("UTC")
        val parts = DayUtils.splitByDay(ms("2026-11-19T22:00:00Z"), ms("2026-11-20T02:00:00Z"), utc)
        assertEquals(listOf("2026-11-19" to 2 * 3_600_000L, "2026-11-20" to 2 * 3_600_000L), parts)
    }

    @Test
    fun `a multi day interval credits every day and the parts add up`() {
        val utc = ZoneId.of("UTC")
        val start = ms("2026-11-19T12:00:00Z")
        val end = ms("2026-11-22T12:00:00Z")
        val parts = DayUtils.splitByDay(start, end, utc)
        assertEquals(
            listOf(
                "2026-11-19" to 12 * 3_600_000L,
                "2026-11-20" to 24 * 3_600_000L,
                "2026-11-21" to 24 * 3_600_000L,
                "2026-11-22" to 12 * 3_600_000L
            ),
            parts
        )
        assertEquals(end - start, parts.sumOf { it.second })
    }

    @Test
    fun `empty or backwards intervals give nothing`() {
        val utc = ZoneId.of("UTC")
        assertEquals(emptyList<Pair<String, Long>>(), DayUtils.splitByDay(1_000, 1_000, utc))
        assertEquals(emptyList<Pair<String, Long>>(), DayUtils.splitByDay(2_000, 1_000, utc))
    }
}
