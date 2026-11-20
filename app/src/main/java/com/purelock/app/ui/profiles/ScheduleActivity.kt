package com.purelock.app.ui.profiles

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.data.db.PROFILE_MOTIVATIONAL_DEFAULT
import com.purelock.app.data.db.SafeZone
import com.purelock.app.databinding.ActivityScheduleBinding
import com.purelock.app.databinding.ItemSfIntervalBinding
import com.purelock.app.service.ConditionEvaluator
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.apps.ConditionActivity
import com.purelock.app.ui.apps.ConditionResult
import com.purelock.app.ui.apps.toIntent
import com.purelock.app.ui.location.MapActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Set Blocking Schedule (SPEC S20/S21): I Want to Block, On Days, one
 * condition-specific section, motivational text, Next.
 *
 * Two modes (see the contract in AppListActivity.openSchedule):
 * - App-rule mode (`EXTRA_MODE = MODE_APP_RULE`, the default): Next returns
 *   RESULT_OK with the full rule through the same [ConditionActivity.EXTRA_*]
 *   keys, readable with [ConditionActivity.resultFrom]; the schedule-only values
 *   (I Want to Block, motivational text, Combined) ride as [ScheduleActivity]
 *   extras for callers that read them. Used by All Apps (and later All Sites).
 * - Profile mode (`EXTRA_MODE = MODE_PROFILE`, set by the create flow): Next
 *   forwards the draft to [PickAppsSitesActivity].
 *
 * Variants by analogy (FOUNDATION decision 7): Usage Limit → Once I Have Spent
 * wheel + Per App/Combined; Time Intervals → Full Day + interval list + Add;
 * Quick Block → duration wheel; Launches → per-day/per-hour wheels; Wait Timer →
 * two wheels; Goal Based → app picker + minutes wheel; Location → place picker
 * via [MapActivity] + AT/AWAY + block-if-unavailable.
 */
class ScheduleActivity : AppCompatActivity() {

    private lateinit var b: ActivityScheduleBinding

    private var isProfileMode = false
    private var draft: FocusProfile = ProfileFlow.freshDraft()
    private var combined = false

    private var conditionType = "NONE"
    private var intervalDays = 0
    private var intervals = ArrayList<Pair<Int, Int>>()
    private var fullDay = false
    private var hourlyLimitMinutes = 0
    private var quickMinutes = 10
    private var goalPkg: String? = null
    private var goalLabel: String? = null
    private var zoneId: Long = 0
    private var zoneName: String? = null
    private var zones: List<SafeZone> = emptyList()
    private var launcherApps: List<Pair<String, String>> = emptyList()

    private val mapLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { loadZones() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }

        isProfileMode = intent.getStringExtra(EXTRA_MODE) == MODE_PROFILE
        val (incomingDraft, incomingCombined) = ProfileFlow.draftFrom(intent)
        draft = incomingDraft
        combined = incomingCombined
        if (isProfileMode && draft.name.isBlank()) {
            Toast.makeText(this, R.string.sf_profile_need_name, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        readInitial(intent)
        buildDayCircles()
        setupSections()
        setupInputs()
        loadZones()
        loadLauncherApps()

        b.btnNext.setOnClickListener { next() }
    }

    // ------------------------------------------------------------------ initial values

    private fun readInitial(intent: Intent) {
        val cond = ConditionActivity.resultFrom(intent)
        conditionType = cond?.conditionType ?: "NONE"
        intervalDays = cond?.intervalDays ?: 0
        fullDay = cond?.fullDay ?: false
        hourlyLimitMinutes = cond?.hourlyLimitMinutes ?: 0
        if (cond != null && !fullDay && cond.intervalStartMin != cond.intervalEndMin) {
            intervals.add(cond.intervalStartMin to cond.intervalEndMin)
        }
        if (conditionType == "TIME_INTERVAL" && intervals.isEmpty() && !fullDay) {
            intervals.add(DEFAULT_INTERVAL_START to DEFAULT_INTERVAL_END)
        }
        if (cond != null && cond.conditionType == "QUICK_BLOCK" &&
            cond.quickBlockUntilEpochMs > System.currentTimeMillis()
        ) {
            quickMinutes =
                ((cond.quickBlockUntilEpochMs - System.currentTimeMillis()) / 60_000L)
                    .coerceIn(1, 120).toInt()
        }
        goalPkg = cond?.goalAppPackage
        goalLabel = cond?.goalAppLabel
        zoneId = cond?.locationZoneId ?: 0
        zoneName = cond?.locationZoneName
        b.swBlockLaunch.isChecked = intent.getBooleanExtra(EXTRA_BLOCK_LAUNCH, true)
        b.swBlockNotifications.isChecked = intent.getBooleanExtra(EXTRA_BLOCK_NOTIFICATIONS, false)
        val motivation = intent.getStringExtra(EXTRA_MOTIVATIONAL_TEXT).orEmpty()
        b.etMotivation.setText(motivation.ifBlank { PROFILE_MOTIVATIONAL_DEFAULT })
        if (intent.getStringExtra(ConditionActivity.EXTRA_LOCATION_MODE) == "AWAY") {
            b.rbLocAway.isChecked = true
        }
        b.swLocBlockUnavailable.isChecked =
            intent.getBooleanExtra(ConditionActivity.EXTRA_BLOCK_UNAVAILABLE, false)
        b.swFullDay.isChecked = fullDay
    }

    // ------------------------------------------------------------------ sections

    private fun setupSections() {
        b.secIntervals.isVisible = conditionType == "TIME_INTERVAL"
        b.secSpent.isVisible = conditionType == "USAGE_LIMIT"
        b.secQuick.isVisible = conditionType == "QUICK_BLOCK"
        b.secLaunches.isVisible = conditionType == "LAUNCH_LIMIT"
        b.secWait.isVisible = conditionType == "WAIT_TIMER"
        b.secGoal.isVisible = conditionType == "GOAL"
        b.secLocation.isVisible = conditionType == "LOCATION"

        val initial = ConditionActivity.resultFrom(intent)
        when (conditionType) {
            "USAGE_LIMIT" -> {
                val daily = initial?.dailyLimitMinutes ?: 0
                val total = if (daily > 0) daily else DEFAULT_DAILY_LIMIT_MIN
                b.wheelSpentHour.setItems((0..23).map { "$it hr" }, (total / 60).coerceIn(0, 23))
                b.wheelSpentMin.setItems((0..59).map { "$it min" }, (total % 60).coerceIn(0, 59))
                updateSegment()
            }
            "QUICK_BLOCK" -> {
                b.wheelQuickMin.setItems(QUICK_MINUTES.map { "$it min" }, QUICK_MINUTES.indexOf(quickMinutes).coerceAtLeast(0))
            }
            "LAUNCH_LIMIT" -> {
                b.wheelLaunchesDay.setItems(
                    (0..100).map { it.toString() },
                    (initial?.maxLaunchesPerDay ?: DEFAULT_LAUNCHES_DAY).coerceIn(0, 100)
                )
                b.wheelLaunchesHour.setItems(
                    (0..24).map { it.toString() },
                    (initial?.maxLaunchesPerHour ?: DEFAULT_LAUNCHES_HOUR).coerceIn(0, 24)
                )
            }
            "WAIT_TIMER" -> {
                b.wheelWaitAfter.setItems(
                    (1..120).map { "$it min" },
                    ((initial?.waitAfterMinutes ?: DEFAULT_WAIT_AFTER) - 1).coerceIn(0, 119)
                )
                b.wheelWaitFor.setItems(
                    (1..120).map { "$it min" },
                    ((initial?.waitForMinutes ?: DEFAULT_WAIT_FOR) - 1).coerceIn(0, 119)
                )
            }
            "GOAL" -> {
                b.wheelGoalMin.setItems(
                    GOAL_MINUTES.map { "$it min" },
                    GOAL_MINUTES.indexOf(initial?.goalMinutes ?: DEFAULT_GOAL_MINUTES).coerceAtLeast(0)
                )
                refreshGoalButton()
            }
            "LOCATION" -> refreshZoneButton()
        }
        refreshIntervalViews()
    }

    private fun setupInputs() {
        b.swFullDay.setOnCheckedChangeListener { _, checked ->
            fullDay = checked
            refreshIntervalViews()
        }
        b.btnAddInterval.setOnClickListener { pickInterval() }
        b.tvSegPerApp.setOnClickListener { setCombined(false) }
        b.tvSegCombined.setOnClickListener { setCombined(true) }
        b.btnGoalApp.setOnClickListener { pickGoalApp() }
        b.btnLocationZone.setOnClickListener { pickZone() }
    }

    // ------------------------------------------------------------------ On Days

    private fun buildDayCircles() {
        val labels = listOf("M", "T", "W", "T", "F", "S", "S")
        val bits = listOf(
            ConditionEvaluator.DAY_MON, ConditionEvaluator.DAY_TUE, ConditionEvaluator.DAY_WED,
            ConditionEvaluator.DAY_THU, ConditionEvaluator.DAY_FRI, ConditionEvaluator.DAY_SAT,
            ConditionEvaluator.DAY_SUN
        )
        // 0 means "every day" in the evaluator; show all seven selected for it.
        val shownDays = if (intervalDays == 0) ConditionEvaluator.ALL_DAYS else intervalDays
        labels.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                val size = (44 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4 * resources.displayMetrics.density).toInt()
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener {
                    intervalDays = if (intervalDays and bits[i] != 0) {
                        intervalDays and bits[i].inv()
                    } else {
                        intervalDays or bits[i]
                    }
                    refreshDayCircles()
                }
            }
            tv.tag = bits[i]
            b.rowDays.addView(tv)
        }
        intervalDays = shownDays
        refreshDayCircles()
    }

    private fun refreshDayCircles() {
        for (i in 0 until b.rowDays.childCount) {
            val tv = b.rowDays.getChildAt(i) as TextView
            val bit = tv.tag as Int
            val selected = intervalDays and bit != 0
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_sf_day_selected else R.drawable.bg_sf_day_unselected
            )
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) android.R.color.white else R.color.sf_text
                )
            )
        }
    }

    // ------------------------------------------------------------------ intervals

    private fun refreshIntervalViews() {
        val showList = !fullDay
        b.listIntervals.isVisible = showList
        b.btnAddInterval.isVisible = showList
        b.listIntervals.removeAllViews()
        if (!showList) return
        for ((index, interval) in intervals.withIndex()) {
            val row = ItemSfIntervalBinding.inflate(layoutInflater, b.listIntervals, false)
            row.tvInterval.text = getString(
                R.string.sf_profile_summary_interval,
                formatTime12(interval.first),
                formatTime12(interval.second)
            )
            row.btnDeleteInterval.setOnClickListener {
                intervals.removeAt(index)
                refreshIntervalViews()
            }
            b.listIntervals.addView(row.root)
        }
    }

    private fun pickInterval() {
        TimePickerDialog(
            this,
            { _, startH, startM ->
                TimePickerDialog(
                    this,
                    { _, endH, endM ->
                        val start = startH * 60 + startM
                        val end = endH * 60 + endM
                        if (start == end) {
                            Toast.makeText(this, R.string.cond_need_range, Toast.LENGTH_LONG).show()
                        } else {
                            intervals.add(start to end)
                            refreshIntervalViews()
                        }
                    },
                    DEFAULT_INTERVAL_END / 60, DEFAULT_INTERVAL_END % 60, false
                ).apply { setTitle(R.string.sf_profile_interval_end_title) }.show()
            },
            DEFAULT_INTERVAL_START / 60, DEFAULT_INTERVAL_START % 60, false
        ).apply { setTitle(R.string.sf_profile_interval_start_title) }.show()
    }

    // ------------------------------------------------------------------ segment

    private fun setCombined(value: Boolean) {
        combined = value
        updateSegment()
    }

    private fun updateSegment() {
        b.tvSegPerApp.setBackgroundResource(
            if (!combined) R.drawable.bg_sf_segment_selected else 0
        )
        b.tvSegCombined.setBackgroundResource(
            if (combined) R.drawable.bg_sf_segment_selected else 0
        )
        b.tvSegPerApp.setTextColor(
            ContextCompat.getColor(
                this, if (!combined) R.color.sf_primary else R.color.sf_text
            )
        )
        b.tvSegCombined.setTextColor(
            ContextCompat.getColor(
                this, if (combined) R.color.sf_primary else R.color.sf_text
            )
        )
        b.tvSegPerApp.setTypeface(null, if (!combined) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.tvSegCombined.setTypeface(null, if (combined) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // ------------------------------------------------------------------ goal / location pickers

    private fun loadLauncherApps() {
        lifecycleScope.launch {
            val pm = packageManager
            launcherApps = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
        }
    }

    private fun pickGoalApp() {
        if (launcherApps.isEmpty()) {
            Toast.makeText(this, R.string.cond_apps_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = launcherApps.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cond_goal_app)
            .setSingleChoiceItems(labels, launcherApps.indexOfFirst { it.first == goalPkg }) { dialog, which ->
                goalPkg = launcherApps[which].first
                goalLabel = launcherApps[which].second
                refreshGoalButton()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun refreshGoalButton() {
        b.btnGoalApp.text = goalLabel ?: getString(R.string.cond_goal_pick_app)
    }

    private fun loadZones() {
        lifecycleScope.launch {
            zones = withContext(Dispatchers.IO) { Graph.db.safeZoneDao().getAll() }
            if (zoneId > 0) zoneName = zones.firstOrNull { it.id == zoneId }?.name ?: zoneName
            refreshZoneButton()
        }
    }

    private fun refreshZoneButton() {
        b.btnLocationZone.text = zoneName ?: getString(R.string.rule_tap_set_location)
    }

    private fun pickZone() {
        val names = zones.map { "${it.name} · ${it.radiusM} m" }.toMutableList()
        names.add(getString(R.string.cond_zone_open_map))
        names.add(getString(R.string.cond_zone_none))
        AlertDialog.Builder(this)
            .setTitle(R.string.cond_location_zone)
            .setItems(names.toTypedArray()) { dialog, which ->
                when {
                    which < zones.size -> {
                        zoneId = zones[which].id
                        zoneName = zones[which].name
                        refreshZoneButton()
                    }
                    which == zones.size -> mapLauncher.launch(Intent(this, MapActivity::class.java))
                    else -> {
                        zoneId = 0
                        zoneName = null
                        refreshZoneButton()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    // ------------------------------------------------------------------ next

    private fun currentConditionResult(): ConditionResult? {
        val now = System.currentTimeMillis()
        return when (conditionType) {
            "USAGE_LIMIT" -> {
                val daily = b.wheelSpentHour.selectedIndex * 60 + b.wheelSpentMin.selectedIndex
                if (daily <= 0) return null
                ConditionResult(
                    "USAGE_LIMIT",
                    dailyLimitMinutes = daily,
                    hourlyLimitMinutes = hourlyLimitMinutes,
                    intervalDays = intervalDays
                )
            }
            "TIME_INTERVAL" -> {
                if (!fullDay && intervals.isEmpty()) return null
                val first = intervals.firstOrNull() ?: (0 to 0)
                ConditionResult(
                    "TIME_INTERVAL", intervalDays = intervalDays,
                    intervalStartMin = first.first, intervalEndMin = first.second,
                    fullDay = fullDay
                )
            }
            "QUICK_BLOCK" -> {
                quickMinutes = QUICK_MINUTES[b.wheelQuickMin.selectedIndex]
                ConditionResult(
                    "QUICK_BLOCK",
                    quickBlockUntilEpochMs = now + quickMinutes * 60_000L,
                    intervalDays = intervalDays
                )
            }
            "LAUNCH_LIMIT" -> {
                val day = b.wheelLaunchesDay.selectedIndex
                val hour = b.wheelLaunchesHour.selectedIndex
                if (day <= 0 && hour <= 0) return null
                ConditionResult(
                    "LAUNCH_LIMIT",
                    maxLaunchesPerDay = day,
                    maxLaunchesPerHour = hour,
                    intervalDays = intervalDays
                )
            }
            "WAIT_TIMER" -> ConditionResult(
                "WAIT_TIMER",
                waitAfterMinutes = b.wheelWaitAfter.selectedIndex + 1,
                waitForMinutes = b.wheelWaitFor.selectedIndex + 1,
                intervalDays = intervalDays
            )
            "GOAL" -> {
                val minutes = GOAL_MINUTES[b.wheelGoalMin.selectedIndex]
                if (goalPkg == null || minutes <= 0) return null
                ConditionResult(
                    "GOAL",
                    goalAppPackage = goalPkg,
                    goalAppLabel = goalLabel,
                    goalMinutes = minutes,
                    intervalDays = intervalDays
                )
            }
            "LOCATION" -> {
                if (zoneId <= 0) return null
                ConditionResult(
                    "LOCATION",
                    locationZoneId = zoneId,
                    locationZoneName = zoneName,
                    locationMode = if (b.rbLocAway.isChecked) "AWAY" else "AT",
                    blockIfLocationUnavailable = b.swLocBlockUnavailable.isChecked,
                    intervalDays = intervalDays
                )
            }
            else -> ConditionResult("NONE")
        }
    }

    private fun next() {
        val cond = currentConditionResult()
        if (cond == null) {
            val msg = when (conditionType) {
                "TIME_INTERVAL" -> R.string.sf_profile_need_interval
                "GOAL" -> R.string.cond_need_goal
                "LOCATION" -> R.string.cond_need_zone
                else -> R.string.cond_need_value
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (conditionType == "TIME_INTERVAL" && !fullDay && intervals.size > 1) {
            Toast.makeText(this, R.string.sf_profile_interval_kept_one, Toast.LENGTH_LONG).show()
        }
        val result = ScheduleResult(
            condition = cond,
            blockLaunch = b.swBlockLaunch.isChecked,
            blockNotifications = b.swBlockNotifications.isChecked,
            motivationalText = b.etMotivation.text?.toString().orEmpty(),
            combined = combined
        )
        if (isProfileMode) {
            val updated = ProfileFlow.applySchedule(draft, result)
            val intent = Intent(this, PickAppsSitesActivity::class.java)
            ProfileFlow.putDraft(intent, updated, combined)
            startActivity(intent)
        } else {
            setResult(Activity.RESULT_OK, result.toResultIntent())
            finish()
        }
    }

    /** The values this screen returns, as an intent (same keys as [ConditionActivity]). */
    data class ScheduleResult(
        val condition: ConditionResult,
        val blockLaunch: Boolean,
        val blockNotifications: Boolean,
        val motivationalText: String,
        val combined: Boolean
    ) {
        fun toResultIntent(): Intent = condition.toIntent().apply {
            putExtra(EXTRA_BLOCK_LAUNCH, blockLaunch)
            putExtra(EXTRA_BLOCK_NOTIFICATIONS, blockNotifications)
            putExtra(EXTRA_MOTIVATIONAL_TEXT, motivationalText)
            putExtra(EXTRA_COMBINED, combined)
        }
    }

    companion object {
        /** The app/rule the schedule is for (same keys as AppListActivity). */
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MODE = "mode"
        const val MODE_APP_RULE = "app_rule"
        const val MODE_PROFILE = "profile"

        /** "I Want to Block" + motivational text, carried alongside the condition extras. */
        const val EXTRA_BLOCK_LAUNCH = "blockLaunch"
        const val EXTRA_BLOCK_NOTIFICATIONS = "blockNotifications"
        const val EXTRA_MOTIVATIONAL_TEXT = "motivationalText"
        const val EXTRA_COMBINED = ProfileFlow.EXTRA_COMBINED

        /** Reads a schedule result back out of its result intent. */
        fun resultFrom(intent: Intent?): ScheduleResult? {
            val cond = ConditionActivity.resultFrom(intent) ?: return null
            intent ?: return null
            return ScheduleResult(
                condition = cond,
                blockLaunch = intent.getBooleanExtra(EXTRA_BLOCK_LAUNCH, true),
                blockNotifications = intent.getBooleanExtra(EXTRA_BLOCK_NOTIFICATIONS, false),
                motivationalText = intent.getStringExtra(EXTRA_MOTIVATIONAL_TEXT).orEmpty(),
                combined = intent.getBooleanExtra(EXTRA_COMBINED, false)
            )
        }

        private const val DEFAULT_DAILY_LIMIT_MIN = 30
        private const val DEFAULT_INTERVAL_START = 8 * 60
        private const val DEFAULT_INTERVAL_END = 17 * 60
        private const val DEFAULT_LAUNCHES_DAY = 20
        private const val DEFAULT_LAUNCHES_HOUR = 2
        private const val DEFAULT_WAIT_AFTER = 5
        private const val DEFAULT_WAIT_FOR = 30
        private const val DEFAULT_GOAL_MINUTES = 30
        private val QUICK_MINUTES = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
        private val GOAL_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180)
    }
}
