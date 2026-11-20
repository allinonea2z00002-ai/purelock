package com.purelock.app.ui.profiles

import android.content.Context
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.service.ConditionEvaluator

/**
 * Dashboard template prefills (SPEC S01 §15, FOUNDATION decision 10).
 * The display strings live in the dashboard area (`sf_dash_template_*`); the values
 * here are the prefilled profile fields the create flow starts from.
 */
object ProfileTemplates {

    const val FOCUS_HOURS = "FOCUS_HOURS"
    const val SOCIAL_LIMIT = "SOCIAL_LIMIT"

    /** Prefilled draft for a template tag, or null for an unknown tag. */
    fun forTemplate(context: Context, template: String?): FocusProfile? = when (template) {
        FOCUS_HOURS -> focusHours(context)
        SOCIAL_LIMIT -> socialLimit(context)
        else -> null
    }

    /** Weekday work-hours interval: Mon–Fri, 8am–5pm, Blocklist. */
    fun focusHours(context: Context): FocusProfile = FocusProfile(
        name = context.getString(R.string.sf_dash_template_focus),
        type = "BLOCKLIST",
        conditionType = "TIME_INTERVAL",
        intervalDays = ConditionEvaluator.DAY_MON or ConditionEvaluator.DAY_TUE or
            ConditionEvaluator.DAY_WED or ConditionEvaluator.DAY_THU or ConditionEvaluator.DAY_FRI,
        intervalStartMin = 8 * 60,
        intervalEndMin = 17 * 60,
        fullDay = false,
        blockLaunch = true,
        template = FOCUS_HOURS,
        createdEpoch = System.currentTimeMillis()
    )

    /** Every day, 30 minutes per app, Blocklist. */
    fun socialLimit(context: Context): FocusProfile = FocusProfile(
        name = context.getString(R.string.sf_dash_template_social),
        type = "BLOCKLIST",
        conditionType = "USAGE_LIMIT",
        dailyLimitMinutes = 30,
        hourlyLimitMinutes = 0,
        intervalDays = ConditionEvaluator.ALL_DAYS,
        blockLaunch = true,
        template = SOCIAL_LIMIT,
        createdEpoch = System.currentTimeMillis()
    )
}
