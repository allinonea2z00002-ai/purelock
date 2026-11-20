package com.purelock.app.util

/** Plain numeric duration formatting (no localized words, so it is identical in English and Bangla UIs). */
object DurationFormat {

    /** 93_784_000 -> "1d 02:03:04" ; 3_725_000 -> "01:02:05" ; 59_000 -> "00:59". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000 // round up so "00:00" only appears at real zero
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return when {
            days > 0 -> "%dd %02d:%02d:%02d".format(days, hours, minutes, seconds)
            hours > 0 -> "%02d:%02d:%02d".format(hours, minutes, seconds)
            else -> "%02d:%02d".format(minutes, seconds)
        }
    }

    /** Compact total, e.g. "3h 20m" / "2d 4h" / "45m". Letters are universal unit symbols. */
    fun compact(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0) / 60_000
        val days = totalMinutes / 1_440
        val hours = (totalMinutes % 1_440) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun distance(meters: Float): String =
        if (meters >= 1000f) "%.2f km".format(meters / 1000f) else "%d m".format(meters.toInt())
}
