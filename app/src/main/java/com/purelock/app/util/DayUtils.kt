package com.purelock.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar-day helpers. Statistics buckets are keyed by local date, "yyyy-MM-dd". */
object DayUtils {

    fun dateKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun parse(dateKey: String): LocalDate = LocalDate.parse(dateKey)

    /**
     * Splits the interval [startEpoch, endEpoch) into (dateKey, millis) parts, one per local day.
     * Used so a lock that runs across midnight credits each day with its real share.
     */
    fun splitByDay(startEpoch: Long, endEpoch: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, Long>> {
        if (endEpoch <= startEpoch) return emptyList()
        val result = ArrayList<Pair<String, Long>>()
        var cursor = startEpoch
        while (cursor < endEpoch) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val partEnd = minOf(nextMidnight, endEpoch)
            result.add(day.toString() to (partEnd - cursor))
            cursor = partEnd
        }
        return result
    }
}
