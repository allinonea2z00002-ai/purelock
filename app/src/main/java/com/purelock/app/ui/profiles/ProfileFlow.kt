package com.purelock.app.ui.profiles

import android.content.Intent
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.ui.apps.ConditionActivity
import com.purelock.app.ui.apps.ConditionResult

/**
 * The create-flow draft: one [FocusProfile] (+ the "Combined" flag, which lives outside
 * Room) carried forward through the step activities as intent extras, so the back
 * button walks the wizard backwards naturally:
 * Create (name) → ChooseBlockingType → Condition → Schedule → PickAppsSites → save.
 *
 * Condition values travel under the SAME [ConditionActivity.EXTRA_*] keys the chooser
 * uses, so every step can read them with [ConditionActivity.resultFrom] and the
 * schedule screen can be prefilled from a draft.
 */
object ProfileFlow {

    const val EXTRA_PROFILE_ID = "profileId"
    const val EXTRA_PROFILE_NAME = "profileName"
    const val EXTRA_PROFILE_TYPE = "profileType"
    const val EXTRA_PROFILE_TEMPLATE = "profileTemplate"
    /** True when the step was opened from inside the create flow (forward, not result). */
    const val EXTRA_FORWARD = "forward"
    /** Usage Limit applies to the selected apps' combined time (see ProfileRepository). */
    const val EXTRA_COMBINED = "combined"
    const val EXTRA_APP_PACKAGES = "appPackages"
    const val EXTRA_SITE_DOMAINS = "siteDomains"
    const val EXTRA_CREATED_EPOCH = "createdEpoch"
    const val EXTRA_ACTIVE = "profileActive"
    const val EXTRA_PAUSED = "profilePaused"

    /** Writes the draft (+ schedule extras) into an intent that starts the next step. */
    fun putDraft(intent: Intent, draft: FocusProfile, combined: Boolean = false): Intent =
        intent.apply {
            putExtra(EXTRA_PROFILE_ID, draft.id)
            putExtra(EXTRA_PROFILE_NAME, draft.name)
            putExtra(EXTRA_PROFILE_TYPE, draft.type)
            putExtra(EXTRA_PROFILE_TEMPLATE, draft.template)
            putExtra(EXTRA_COMBINED, combined)
            putExtra(EXTRA_APP_PACKAGES, draft.appPackages)
            putExtra(EXTRA_SITE_DOMAINS, draft.siteDomains)
            putExtra(EXTRA_CREATED_EPOCH, draft.createdEpoch)
            putExtra(EXTRA_ACTIVE, draft.active)
            putExtra(EXTRA_PAUSED, draft.paused)
            putExtra(ConditionActivity.EXTRA_CONDITION_TYPE, draft.conditionType)
            putExtra(ConditionActivity.EXTRA_DAILY_LIMIT, draft.dailyLimitMinutes)
            putExtra(ConditionActivity.EXTRA_HOURLY_LIMIT, draft.hourlyLimitMinutes)
            putExtra(ConditionActivity.EXTRA_INTERVAL_DAYS, draft.intervalDays)
            putExtra(ConditionActivity.EXTRA_INTERVAL_START, draft.intervalStartMin)
            putExtra(ConditionActivity.EXTRA_INTERVAL_END, draft.intervalEndMin)
            putExtra(ConditionActivity.EXTRA_FULL_DAY, draft.fullDay)
            if (draft.conditionType == "QUICK_BLOCK" && draft.quickBlockMinutes > 0) {
                putExtra(
                    ConditionActivity.EXTRA_QUICK_UNTIL,
                    System.currentTimeMillis() + draft.quickBlockMinutes * 60_000L
                )
            }
            putExtra(ConditionActivity.EXTRA_LAUNCHES_DAY, draft.maxLaunchesPerDay)
            putExtra(ConditionActivity.EXTRA_LAUNCHES_HOUR, draft.maxLaunchesPerHour)
            putExtra(ConditionActivity.EXTRA_WAIT_AFTER, draft.waitAfterMinutes)
            putExtra(ConditionActivity.EXTRA_WAIT_FOR, draft.waitForMinutes)
            putExtra(ConditionActivity.EXTRA_GOAL_PKG, draft.goalAppPackage)
            putExtra(ConditionActivity.EXTRA_GOAL_MINUTES, draft.goalMinutes)
            putExtra(ConditionActivity.EXTRA_ZONE_ID, draft.locationZoneId)
            putExtra(ConditionActivity.EXTRA_LOCATION_MODE, draft.locationMode)
            putExtra(ConditionActivity.EXTRA_BLOCK_UNAVAILABLE, draft.blockIfLocationUnavailable)
            putExtra(ScheduleActivity.EXTRA_BLOCK_LAUNCH, draft.blockLaunch)
            putExtra(ScheduleActivity.EXTRA_BLOCK_NOTIFICATIONS, draft.blockNotifications)
            putExtra(ScheduleActivity.EXTRA_MOTIVATIONAL_TEXT, draft.motivationalText)
        }

    /** Reads the draft back out of a step intent (defaults = a fresh Blocklist profile). */
    fun draftFrom(intent: Intent?): Pair<FocusProfile, Boolean> {
        intent ?: return freshDraft() to false
        val cond = ConditionActivity.resultFrom(intent)
        val quickMinutes = cond?.let {
            if (it.conditionType == "QUICK_BLOCK" && it.quickBlockUntilEpochMs > System.currentTimeMillis()) {
                ((it.quickBlockUntilEpochMs - System.currentTimeMillis()) / 60_000L).toInt()
            } else {
                0
            }
        } ?: 0
        val draft = FocusProfile(
            id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L),
            name = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty(),
            type = intent.getStringExtra(EXTRA_PROFILE_TYPE) ?: "BLOCKLIST",
            conditionType = cond?.conditionType ?: "NONE",
            dailyLimitMinutes = cond?.dailyLimitMinutes ?: 0,
            hourlyLimitMinutes = cond?.hourlyLimitMinutes ?: 0,
            intervalDays = cond?.intervalDays ?: 0,
            intervalStartMin = cond?.intervalStartMin ?: 0,
            intervalEndMin = cond?.intervalEndMin ?: 0,
            fullDay = cond?.fullDay ?: false,
            quickBlockMinutes = quickMinutes,
            maxLaunchesPerDay = cond?.maxLaunchesPerDay ?: 0,
            maxLaunchesPerHour = cond?.maxLaunchesPerHour ?: 0,
            waitAfterMinutes = cond?.waitAfterMinutes ?: 0,
            waitForMinutes = cond?.waitForMinutes ?: 0,
            goalAppPackage = cond?.goalAppPackage,
            goalMinutes = cond?.goalMinutes ?: 0,
            locationZoneId = cond?.locationZoneId ?: 0,
            locationMode = cond?.locationMode ?: "AT",
            blockIfLocationUnavailable = cond?.blockIfLocationUnavailable ?: false,
            blockLaunch = intent.getBooleanExtra(ScheduleActivity.EXTRA_BLOCK_LAUNCH, true),
            blockNotifications = intent.getBooleanExtra(ScheduleActivity.EXTRA_BLOCK_NOTIFICATIONS, false),
            motivationalText = intent.getStringExtra(ScheduleActivity.EXTRA_MOTIVATIONAL_TEXT).orEmpty(),
            appPackages = intent.getStringExtra(EXTRA_APP_PACKAGES).orEmpty(),
            siteDomains = intent.getStringExtra(EXTRA_SITE_DOMAINS).orEmpty(),
            template = intent.getStringExtra(EXTRA_PROFILE_TEMPLATE),
            active = intent.getBooleanExtra(EXTRA_ACTIVE, false),
            paused = intent.getBooleanExtra(EXTRA_PAUSED, false),
            createdEpoch = intent.getLongExtra(EXTRA_CREATED_EPOCH, System.currentTimeMillis())
        )
        return draft to intent.getBooleanExtra(EXTRA_COMBINED, false)
    }

    fun freshDraft(): FocusProfile =
        FocusProfile(name = "", type = "BLOCKLIST", createdEpoch = System.currentTimeMillis())

    /** Merges a chooser result into a draft (same field mapping as AppListActivity). */
    fun applyCondition(draft: FocusProfile, cond: ConditionResult): FocusProfile {
        if (cond.conditionType == "NONE") {
            return draft.copy(
                conditionType = "NONE", dailyLimitMinutes = 0, hourlyLimitMinutes = 0,
                intervalDays = 0, intervalStartMin = 0, intervalEndMin = 0, fullDay = false,
                quickBlockMinutes = 0, maxLaunchesPerDay = 0, maxLaunchesPerHour = 0,
                waitAfterMinutes = 0, waitForMinutes = 0, goalAppPackage = null, goalMinutes = 0,
                locationZoneId = 0, locationMode = "AT", blockIfLocationUnavailable = false
            )
        }
        val quickMinutes =
            if (cond.conditionType == "QUICK_BLOCK" && cond.quickBlockUntilEpochMs > System.currentTimeMillis()) {
                ((cond.quickBlockUntilEpochMs - System.currentTimeMillis()) / 60_000L).toInt()
            } else {
                draft.quickBlockMinutes
            }
        return draft.copy(
            conditionType = cond.conditionType,
            dailyLimitMinutes = cond.dailyLimitMinutes,
            hourlyLimitMinutes = cond.hourlyLimitMinutes,
            intervalDays = cond.intervalDays,
            intervalStartMin = cond.intervalStartMin,
            intervalEndMin = cond.intervalEndMin,
            fullDay = cond.fullDay,
            quickBlockMinutes = quickMinutes,
            maxLaunchesPerDay = cond.maxLaunchesPerDay,
            maxLaunchesPerHour = cond.maxLaunchesPerHour,
            waitAfterMinutes = cond.waitAfterMinutes,
            waitForMinutes = cond.waitForMinutes,
            goalAppPackage = cond.goalAppPackage,
            goalMinutes = cond.goalMinutes,
            locationZoneId = cond.locationZoneId,
            locationMode = cond.locationMode,
            blockIfLocationUnavailable = cond.blockIfLocationUnavailable
        )
    }

    /** Merges a schedule result (condition + I-Want-to-Block + motivational text) into a draft. */
    fun applySchedule(draft: FocusProfile, result: ScheduleActivity.ScheduleResult): FocusProfile =
        applyCondition(draft, result.condition).copy(
            blockLaunch = result.blockLaunch,
            blockNotifications = result.blockNotifications,
            motivationalText = result.motivationalText
        )
}
