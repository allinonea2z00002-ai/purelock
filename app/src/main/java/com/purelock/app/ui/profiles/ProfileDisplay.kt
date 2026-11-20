package com.purelock.app.ui.profiles

import android.content.Context
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile

/**
 * Dashboard profile-card schedule summaries (SPEC S01 §13: `01 mins daily`,
 * `Activate` when inactive). Used by the Dashboard (Area A) for
 * `item_sf_profile.xml`'s `tvProfileSchedule`; the entity itself is coordinator-owned
 * so the summary lives here, not on [FocusProfile].
 *
 * `zoneName` / `goalLabel` are best-effort display names the caller may know (the
 * profile only stores the zone id / goal package); when unknown the generic
 * "At this place" / package text is used.
 */
fun FocusProfile.scheduleSummary(
    context: Context,
    zoneName: String? = null,
    goalLabel: String? = null
): String = when (conditionType) {
    "USAGE_LIMIT" -> when {
        dailyLimitMinutes > 0 ->
            context.getString(R.string.sf_dash_mins_daily, dailyLimitMinutes)
        hourlyLimitMinutes > 0 ->
            context.getString(R.string.sf_profile_summary_mins_hourly, hourlyLimitMinutes)
        else -> context.getString(R.string.sf_profile_summary_none)
    }
    "TIME_INTERVAL" -> when {
        fullDay -> context.getString(R.string.sf_profile_summary_all_day)
        intervalStartMin != intervalEndMin -> context.getString(
            R.string.sf_profile_summary_interval,
            formatTime12(intervalStartMin),
            formatTime12(intervalEndMin)
        )
        else -> context.getString(R.string.sf_profile_summary_none)
    }
    "QUICK_BLOCK" -> if (quickBlockMinutes > 0) {
        context.getString(R.string.sf_dash_quick_block_mins, quickBlockMinutes)
    } else {
        context.getString(R.string.sf_profile_summary_none)
    }
    "LAUNCH_LIMIT" -> when {
        maxLaunchesPerDay > 0 ->
            context.getString(R.string.sf_dash_launches_daily, maxLaunchesPerDay)
        maxLaunchesPerHour > 0 ->
            context.getString(R.string.sf_profile_summary_launches_hourly, maxLaunchesPerHour)
        else -> context.getString(R.string.sf_profile_summary_none)
    }
    "WAIT_TIMER" -> if (waitAfterMinutes > 0 && waitForMinutes > 0) {
        context.getString(
            R.string.sf_profile_summary_wait,
            waitForMinutes,
            waitAfterMinutes
        )
    } else {
        context.getString(R.string.sf_profile_summary_none)
    }
    "GOAL" -> if (goalMinutes > 0) {
        context.getString(
            R.string.sf_profile_summary_goal,
            goalMinutes,
            goalLabel ?: goalAppPackage.orEmpty()
        )
    } else {
        context.getString(R.string.sf_profile_summary_none)
    }
    "LOCATION" -> if (locationZoneId > 0) {
        if (zoneName != null) {
            context.getString(
                if (locationMode == "AWAY") R.string.sf_profile_summary_away_place
                else R.string.sf_profile_summary_at_place,
                zoneName
            )
        } else {
            context.getString(
                if (locationMode == "AWAY") R.string.cond_location_away
                else R.string.cond_location_at
            )
        }
    } else {
        context.getString(R.string.sf_profile_summary_none)
    }
    else -> context.getString(R.string.sf_profile_summary_none)
}

/** Minutes since midnight as `08 AM` / `05 PM` / `09:30 AM` (SPEC S20 interval rows). */
fun formatTime12(minutesSinceMidnight: Int): String {
    val hour = (minutesSinceMidnight / 60).coerceIn(0, 23)
    val minute = (minutesSinceMidnight % 60).coerceIn(0, 59)
    val hour12 = hour % 12
    val shown = if (hour12 == 0) 12 else hour12
    val amPm = if (hour < 12) "AM" else "PM"
    return if (minute == 0) "%02d %s".format(shown, amPm)
    else "%02d:%02d %s".format(shown, minute, amPm)
}
