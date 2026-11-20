package com.purelock.app.ui.apps

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SafeZone
import com.purelock.app.databinding.ActivityConditionBinding
import com.purelock.app.databinding.ItemConditionBinding
import com.purelock.app.service.ConditionEvaluator
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.location.MapActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The values the chooser returns to [AppRuleActivity]. One field set per condition; fields of
 * other conditions keep their previous values unless the condition is cleared (NONE).
 */
data class ConditionResult(
    val conditionType: String = "NONE",
    val dailyLimitMinutes: Int = 0,
    val hourlyLimitMinutes: Int = 0,
    val intervalDays: Int = 0,
    val intervalStartMin: Int = 0,
    val intervalEndMin: Int = 0,
    val fullDay: Boolean = false,
    val quickBlockUntilEpochMs: Long = 0,
    val maxLaunchesPerDay: Int = 0,
    val maxLaunchesPerHour: Int = 0,
    val waitAfterMinutes: Int = 0,
    val waitForMinutes: Int = 0,
    val goalAppPackage: String? = null,
    val goalAppLabel: String? = null,
    val goalMinutes: Int = 0,
    val locationZoneId: Long = 0,
    val locationZoneName: String? = null,
    val locationMode: String = "AT",
    val blockIfLocationUnavailable: Boolean = false
)

/**
 * "Choose Blocking Condition" (Stay Focused screenshot 5). Tapping a condition shows its config
 * below the list — Usage Limit opens the Daily Limit / Hourly Limit form (screenshot 6) — and
 * Save returns the values to the rule screen. Nothing is written to the database here.
 */
class ConditionActivity : AppCompatActivity() {

    private lateinit var b: ActivityConditionBinding

    private var selectedType: String = "NONE"
    private var intervalDays: Int = 0
    private var intervalStartMin: Int = 0
    private var intervalEndMin: Int = 0
    private var goalPkg: String? = null
    private var goalLabel: String? = null
    private var zoneId: Long = 0
    private var zoneName: String? = null
    private var zones: List<SafeZone> = emptyList()
    private var launcherApps: List<Pair<String, String>> = emptyList()

    private val mapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        loadZones() // the user may have added a zone on the map
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityConditionBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.toolbarCondition.setNavigationOnClickListener { finish() }

        readInitial(intent)
        buildDayChips()
        prefill()
        setupList()
        setupInputs()
        select(selectedType, scroll = false)
        loadZones()
        loadLauncherApps()

        b.btnSaveCondition.setOnClickListener { save() }
        b.btnClearCondition.setOnClickListener {
            selectedType = "NONE"
            select("NONE", scroll = false)
            b.listConditions.adapter?.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ initial values

    private fun readInitial(intent: Intent) {
        selectedType = intent.getStringExtra(EXTRA_CONDITION_TYPE) ?: "NONE"
        intervalDays = intent.getIntExtra(EXTRA_INTERVAL_DAYS, 0)
        intervalStartMin = intent.getIntExtra(EXTRA_INTERVAL_START, 0)
        intervalEndMin = intent.getIntExtra(EXTRA_INTERVAL_END, 0)
        goalPkg = intent.getStringExtra(EXTRA_GOAL_PKG)
        goalLabel = intent.getStringExtra(EXTRA_GOAL_LABEL)
        zoneId = intent.getLongExtra(EXTRA_ZONE_ID, 0)
        zoneName = intent.getStringExtra(EXTRA_ZONE_NAME)
        b.etDailyLimit.setText(numOrEmpty(intent.getIntExtra(EXTRA_DAILY_LIMIT, 0)))
        b.etHourlyLimit.setText(numOrEmpty(intent.getIntExtra(EXTRA_HOURLY_LIMIT, 0)))
        b.etLaunchesDay.setText(numOrEmpty(intent.getIntExtra(EXTRA_LAUNCHES_DAY, 0)))
        b.etLaunchesHour.setText(numOrEmpty(intent.getIntExtra(EXTRA_LAUNCHES_HOUR, 0)))
        b.etWaitAfter.setText(numOrEmpty(intent.getIntExtra(EXTRA_WAIT_AFTER, 0)))
        b.etWaitFor.setText(numOrEmpty(intent.getIntExtra(EXTRA_WAIT_FOR, 0)))
        b.etGoalMinutes.setText(numOrEmpty(intent.getIntExtra(EXTRA_GOAL_MINUTES, 0)))
        val quickUntil = intent.getLongExtra(EXTRA_QUICK_UNTIL, 0)
        if (quickUntil > System.currentTimeMillis()) {
            b.etQuickMinutes.setText(((quickUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1).toString())
        }
        b.swFullDay.isChecked = intent.getBooleanExtra(EXTRA_FULL_DAY, false)
        b.swBlockUnavailable.isChecked = intent.getBooleanExtra(EXTRA_BLOCK_UNAVAILABLE, false)
        if (intent.getStringExtra(EXTRA_LOCATION_MODE) == "AWAY") b.rgLocationMode.check(R.id.rbModeAway)
    }

    private fun numOrEmpty(v: Int): String = if (v > 0) v.toString() else ""

    private fun prefill() {
        refreshIntervalButtons()
        refreshGoalButton()
        refreshZoneButton()
    }

    // ------------------------------------------------------------------ condition list

    private data class ConditionItem(
        val type: String, val titleRes: Int, val subRes: Int, val iconRes: Int, val tintAttr: Int
    )

    private fun conditions() = listOf(
        ConditionItem("NONE", R.string.cond_none, R.string.cond_none_sub, R.drawable.ic_check_circle, R.attr.plTextSecondary),
        ConditionItem("USAGE_LIMIT", R.string.cond_usage_limit, R.string.cond_usage_limit_sub, R.drawable.ic_clock, R.attr.plDanger),
        ConditionItem("TIME_INTERVAL", R.string.cond_time_interval, R.string.cond_time_interval_sub, R.drawable.ic_menu, com.google.android.material.R.attr.colorPrimary),
        ConditionItem("QUICK_BLOCK", R.string.cond_quick_block, R.string.cond_quick_block_sub, R.drawable.ic_shield_lock, R.attr.plWarning),
        ConditionItem("LAUNCH_LIMIT", R.string.cond_launch_limit, R.string.cond_launch_limit_sub, R.drawable.ic_grid, R.attr.plSuccess),
        ConditionItem("WAIT_TIMER", R.string.cond_wait_timer, R.string.cond_wait_timer_sub, R.drawable.ic_clock, R.attr.plDanger),
        ConditionItem("GOAL", R.string.cond_goal, R.string.cond_goal_sub, R.drawable.ic_trophy, com.google.android.material.R.attr.colorPrimary),
        ConditionItem("LOCATION", R.string.cond_location, R.string.cond_location_sub, R.drawable.ic_pin, R.attr.plSuccess)
    )

    private fun setupList() {
        val items = conditions()
        b.listConditions.layoutManager = LinearLayoutManager(this)
        b.listConditions.adapter = object : RecyclerView.Adapter<ConditionVH>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ConditionVH {
                val binding = ItemConditionBinding.inflate(layoutInflater, parent, false)
                return ConditionVH(binding)
            }

            override fun getItemCount(): Int = items.size

            override fun onBindViewHolder(holder: ConditionVH, position: Int) {
                val item = items[position]
                holder.bind(item, item.type == selectedType) {
                    selectedType = item.type
                    select(item.type, scroll = true)
                    notifyDataSetChanged()
                }
            }
        }
    }

    private inner class ConditionVH(private val vb: ItemConditionBinding) : RecyclerView.ViewHolder(vb.root) {
        fun bind(item: ConditionItem, selected: Boolean, onClick: () -> Unit) {
            vb.tvCondTitle.setText(item.titleRes)
            vb.tvCondSub.setText(item.subRes)
            vb.ivCondIcon.setImageResource(item.iconRes)
            vb.ivCondIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(vb.root, item.tintAttr, 0)
            )
            vb.ivCondCheck.isVisible = selected
            vb.cardCondition.setOnClickListener { onClick() }
        }
    }

    private fun select(type: String, scroll: Boolean) {
        selectedType = type
        val showConfig = type != "NONE"
        b.cardConfig.isVisible = showConfig
        if (!showConfig) return
        b.tvConfigTitle.setText(conditions().first { it.type == type }.titleRes)
        b.secUsageLimit.isVisible = type == "USAGE_LIMIT"
        b.secInterval.isVisible = type == "TIME_INTERVAL"
        b.secQuickBlock.isVisible = type == "QUICK_BLOCK"
        b.secLaunches.isVisible = type == "LAUNCH_LIMIT"
        b.secWait.isVisible = type == "WAIT_TIMER"
        b.secGoal.isVisible = type == "GOAL"
        b.secLocation.isVisible = type == "LOCATION"
        if (scroll) b.cardConfig.post { b.cardConfig.requestFocus() }
    }

    // ------------------------------------------------------------------ inputs

    private fun setupInputs() {
        b.swFullDay.setOnCheckedChangeListener { _, checked ->
            b.btnIntervalStart.isEnabled = !checked
            b.btnIntervalEnd.isEnabled = !checked
        }
        b.btnIntervalStart.isEnabled = !b.swFullDay.isChecked
        b.btnIntervalEnd.isEnabled = !b.swFullDay.isChecked
        b.btnIntervalStart.setOnClickListener { pickTime(intervalStartMin) { intervalStartMin = it; refreshIntervalButtons() } }
        b.btnIntervalEnd.setOnClickListener { pickTime(intervalEndMin) { intervalEndMin = it; refreshIntervalButtons() } }
        b.btnGoalApp.setOnClickListener { pickGoalApp() }
        b.btnLocationZone.setOnClickListener { pickZone() }
    }

    private fun buildDayChips() {
        val labels = listOf("M", "T", "W", "T", "F", "S", "S")
        val bits = listOf(
            ConditionEvaluator.DAY_MON, ConditionEvaluator.DAY_TUE, ConditionEvaluator.DAY_WED,
            ConditionEvaluator.DAY_THU, ConditionEvaluator.DAY_FRI, ConditionEvaluator.DAY_SAT,
            ConditionEvaluator.DAY_SUN
        )
        labels.forEachIndexed { i, label ->
            val chip = layoutInflater.inflate(R.layout.chip_choice, b.chipGroupDays, false) as Chip
            chip.text = label
            chip.isChecked = intervalDays and bits[i] != 0
            chip.setOnCheckedChangeListener { _, checked ->
                intervalDays = if (checked) intervalDays or bits[i] else intervalDays and bits[i].inv()
            }
            b.chipGroupDays.addView(chip)
        }
    }

    private fun pickTime(currentMin: Int, onPicked: (Int) -> Unit) {
        val hour = (currentMin / 60).coerceIn(0, 23)
        val minute = (currentMin % 60).coerceIn(0, 59)
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, hour, minute, true).show()
    }

    private fun refreshIntervalButtons() {
        b.btnIntervalStart.text = getString(R.string.rule_interval_start_value, formatMin(intervalStartMin))
        b.btnIntervalEnd.text = getString(R.string.rule_interval_end_value, formatMin(intervalEndMin))
    }

    private fun formatMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun refreshGoalButton() {
        b.btnGoalApp.text = goalLabel ?: getString(R.string.cond_goal_pick_app)
    }

    private fun refreshZoneButton() {
        b.btnLocationZone.text = zoneName ?: getString(R.string.rule_tap_set_location)
    }

    private fun loadZones() {
        lifecycleScope.launch {
            zones = withContext(Dispatchers.IO) { Graph.db.safeZoneDao().getAll() }
            if (zoneId > 0) zoneName = zones.firstOrNull { it.id == zoneId }?.name ?: zoneName
            refreshZoneButton()
        }
    }

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
        val checked = launcherApps.indexOfFirst { it.first == goalPkg }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.cond_goal_app)
            .setSingleChoiceItems(labels, if (goalPkg == null) -1 else checked) { dialog, which ->
                goalPkg = launcherApps[which].first
                goalLabel = launcherApps[which].second
                refreshGoalButton()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
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

    // ------------------------------------------------------------------ save

    private fun num(edit: android.widget.EditText): Int =
        edit.text.toString().toIntOrNull()?.coerceIn(0, 10_000) ?: 0

    private fun save() {
        val now = System.currentTimeMillis()
        val result = when (selectedType) {
            "USAGE_LIMIT" -> {
                val daily = num(b.etDailyLimit).coerceIn(0, 1_440)
                val hourly = num(b.etHourlyLimit).coerceIn(0, 1_440)
                if (daily <= 0 && hourly <= 0) return toast(R.string.cond_need_value)
                ConditionResult("USAGE_LIMIT", dailyLimitMinutes = daily, hourlyLimitMinutes = hourly)
            }
            "TIME_INTERVAL" -> {
                val fullDay = b.swFullDay.isChecked
                if (!fullDay && intervalStartMin == intervalEndMin) return toast(R.string.cond_need_range)
                ConditionResult(
                    "TIME_INTERVAL", intervalDays = intervalDays,
                    intervalStartMin = intervalStartMin, intervalEndMin = intervalEndMin, fullDay = fullDay
                )
            }
            "QUICK_BLOCK" -> {
                val minutes = num(b.etQuickMinutes).coerceIn(0, 1_440)
                if (minutes <= 0) return toast(R.string.cond_need_value)
                ConditionResult("QUICK_BLOCK", quickBlockUntilEpochMs = now + minutes * 60_000L)
            }
            "LAUNCH_LIMIT" -> {
                val day = num(b.etLaunchesDay)
                val hour = num(b.etLaunchesHour)
                if (day <= 0 && hour <= 0) return toast(R.string.cond_need_value)
                ConditionResult("LAUNCH_LIMIT", maxLaunchesPerDay = day, maxLaunchesPerHour = hour)
            }
            "WAIT_TIMER" -> {
                val after = num(b.etWaitAfter).coerceIn(0, 1_440)
                val forMin = num(b.etWaitFor).coerceIn(0, 1_440)
                if (after <= 0 || forMin <= 0) return toast(R.string.cond_need_value)
                ConditionResult("WAIT_TIMER", waitAfterMinutes = after, waitForMinutes = forMin)
            }
            "GOAL" -> {
                val minutes = num(b.etGoalMinutes).coerceIn(0, 1_440)
                if (goalPkg == null || minutes <= 0) return toast(R.string.cond_need_goal)
                ConditionResult("GOAL", goalAppPackage = goalPkg, goalAppLabel = goalLabel, goalMinutes = minutes)
            }
            "LOCATION" -> {
                if (zoneId <= 0) return toast(R.string.cond_need_zone)
                ConditionResult(
                    "LOCATION", locationZoneId = zoneId, locationZoneName = zoneName,
                    locationMode = if (b.rgLocationMode.checkedRadioButtonId == R.id.rbModeAway) "AWAY" else "AT",
                    blockIfLocationUnavailable = b.swBlockUnavailable.isChecked
                )
            }
            else -> ConditionResult("NONE")
        }
        setResult(Activity.RESULT_OK, result.toIntent())
        finish()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_CONDITION_TYPE = "conditionType"
        const val EXTRA_DAILY_LIMIT = "dailyLimitMinutes"
        const val EXTRA_HOURLY_LIMIT = "hourlyLimitMinutes"
        const val EXTRA_INTERVAL_DAYS = "intervalDays"
        const val EXTRA_INTERVAL_START = "intervalStartMin"
        const val EXTRA_INTERVAL_END = "intervalEndMin"
        const val EXTRA_FULL_DAY = "fullDay"
        const val EXTRA_QUICK_UNTIL = "quickBlockUntilEpochMs"
        const val EXTRA_LAUNCHES_DAY = "maxLaunchesPerDay"
        const val EXTRA_LAUNCHES_HOUR = "maxLaunchesPerHour"
        const val EXTRA_WAIT_AFTER = "waitAfterMinutes"
        const val EXTRA_WAIT_FOR = "waitForMinutes"
        const val EXTRA_GOAL_PKG = "goalAppPackage"
        const val EXTRA_GOAL_LABEL = "goalAppLabel"
        const val EXTRA_GOAL_MINUTES = "goalMinutes"
        const val EXTRA_ZONE_ID = "locationZoneId"
        const val EXTRA_ZONE_NAME = "locationZoneName"
        const val EXTRA_LOCATION_MODE = "locationMode"
        const val EXTRA_BLOCK_UNAVAILABLE = "blockIfLocationUnavailable"

        /** Packs a full rule into an intent that starts the chooser. */
        fun intentFor(context: Context, rule: com.purelock.app.data.db.BlockedApplication?): Intent =
            Intent(context, ConditionActivity::class.java).apply {
                rule ?: return@apply
                putExtra(EXTRA_CONDITION_TYPE, rule.conditionType)
                putExtra(EXTRA_DAILY_LIMIT, rule.dailyLimitMinutes)
                putExtra(EXTRA_HOURLY_LIMIT, rule.hourlyLimitMinutes)
                putExtra(EXTRA_INTERVAL_DAYS, rule.intervalDays)
                putExtra(EXTRA_INTERVAL_START, rule.intervalStartMin)
                putExtra(EXTRA_INTERVAL_END, rule.intervalEndMin)
                putExtra(EXTRA_FULL_DAY, rule.fullDay)
                putExtra(EXTRA_QUICK_UNTIL, rule.quickBlockUntilEpochMs)
                putExtra(EXTRA_LAUNCHES_DAY, rule.maxLaunchesPerDay)
                putExtra(EXTRA_LAUNCHES_HOUR, rule.maxLaunchesPerHour)
                putExtra(EXTRA_WAIT_AFTER, rule.waitAfterMinutes)
                putExtra(EXTRA_WAIT_FOR, rule.waitForMinutes)
                putExtra(EXTRA_GOAL_PKG, rule.goalAppPackage)
                putExtra(EXTRA_GOAL_MINUTES, rule.goalMinutes)
                putExtra(EXTRA_ZONE_ID, rule.locationZoneId)
                putExtra(EXTRA_LOCATION_MODE, rule.locationMode)
                putExtra(EXTRA_BLOCK_UNAVAILABLE, rule.blockIfLocationUnavailable)
            }

        /** Reads a chooser result back out of its result intent. */
        fun resultFrom(intent: Intent?): ConditionResult? {
            intent ?: return null
            return ConditionResult(
                conditionType = intent.getStringExtra(EXTRA_CONDITION_TYPE) ?: "NONE",
                dailyLimitMinutes = intent.getIntExtra(EXTRA_DAILY_LIMIT, 0),
                hourlyLimitMinutes = intent.getIntExtra(EXTRA_HOURLY_LIMIT, 0),
                intervalDays = intent.getIntExtra(EXTRA_INTERVAL_DAYS, 0),
                intervalStartMin = intent.getIntExtra(EXTRA_INTERVAL_START, 0),
                intervalEndMin = intent.getIntExtra(EXTRA_INTERVAL_END, 0),
                fullDay = intent.getBooleanExtra(EXTRA_FULL_DAY, false),
                quickBlockUntilEpochMs = intent.getLongExtra(EXTRA_QUICK_UNTIL, 0),
                maxLaunchesPerDay = intent.getIntExtra(EXTRA_LAUNCHES_DAY, 0),
                maxLaunchesPerHour = intent.getIntExtra(EXTRA_LAUNCHES_HOUR, 0),
                waitAfterMinutes = intent.getIntExtra(EXTRA_WAIT_AFTER, 0),
                waitForMinutes = intent.getIntExtra(EXTRA_WAIT_FOR, 0),
                goalAppPackage = intent.getStringExtra(EXTRA_GOAL_PKG),
                goalAppLabel = intent.getStringExtra(EXTRA_GOAL_LABEL),
                goalMinutes = intent.getIntExtra(EXTRA_GOAL_MINUTES, 0),
                locationZoneId = intent.getLongExtra(EXTRA_ZONE_ID, 0),
                locationZoneName = intent.getStringExtra(EXTRA_ZONE_NAME),
                locationMode = intent.getStringExtra(EXTRA_LOCATION_MODE) ?: "AT",
                blockIfLocationUnavailable = intent.getBooleanExtra(EXTRA_BLOCK_UNAVAILABLE, false)
            )
        }
    }
}

/** Packs this result into an intent for setResult. */
private fun ConditionResult.toIntent(): Intent = Intent().apply {
    putExtra(ConditionActivity.EXTRA_CONDITION_TYPE, conditionType)
    putExtra(ConditionActivity.EXTRA_DAILY_LIMIT, dailyLimitMinutes)
    putExtra(ConditionActivity.EXTRA_HOURLY_LIMIT, hourlyLimitMinutes)
    putExtra(ConditionActivity.EXTRA_INTERVAL_DAYS, intervalDays)
    putExtra(ConditionActivity.EXTRA_INTERVAL_START, intervalStartMin)
    putExtra(ConditionActivity.EXTRA_INTERVAL_END, intervalEndMin)
    putExtra(ConditionActivity.EXTRA_FULL_DAY, fullDay)
    putExtra(ConditionActivity.EXTRA_QUICK_UNTIL, quickBlockUntilEpochMs)
    putExtra(ConditionActivity.EXTRA_LAUNCHES_DAY, maxLaunchesPerDay)
    putExtra(ConditionActivity.EXTRA_LAUNCHES_HOUR, maxLaunchesPerHour)
    putExtra(ConditionActivity.EXTRA_WAIT_AFTER, waitAfterMinutes)
    putExtra(ConditionActivity.EXTRA_WAIT_FOR, waitForMinutes)
    putExtra(ConditionActivity.EXTRA_GOAL_PKG, goalAppPackage)
    putExtra(ConditionActivity.EXTRA_GOAL_LABEL, goalAppLabel)
    putExtra(ConditionActivity.EXTRA_GOAL_MINUTES, goalMinutes)
    putExtra(ConditionActivity.EXTRA_ZONE_ID, locationZoneId)
    putExtra(ConditionActivity.EXTRA_ZONE_NAME, locationZoneName)
    putExtra(ConditionActivity.EXTRA_LOCATION_MODE, locationMode)
    putExtra(ConditionActivity.EXTRA_BLOCK_UNAVAILABLE, blockIfLocationUnavailable)
}
