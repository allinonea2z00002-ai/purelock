package com.purelock.app.ui.apps

import android.app.Activity
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.databinding.ActivityAppRuleBinding
import com.purelock.app.service.ConditionEvaluator
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mockup screen 5: App Blocker detail as a full screen, extended with the Stay Focused rule
 * screen (screenshot 7): "I Want to Block" (App Launch / Notification), On Days, In Intervals,
 * "Whenever I am on Location" and "Block if location is unavailable". The blocking condition
 * itself is picked in [ConditionActivity]; this screen holds the working copy ([draft]) and
 * saves it through [AppListViewModel.saveRule].
 *
 * Notification blocking is stored only: PureLock has no notification listener, so the toggle
 * cannot hide notifications yet, and the UI says so honestly.
 */
class AppRuleActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppRuleBinding
    private val vm: AppListViewModel by viewModels()
    private var item: AppItem? = null
    /** Working copy of the rule; edited here and in ConditionActivity, saved on Save Rule. */
    private var draft: BlockedApplication? = null
    private var zoneName: String? = null

    private val conditionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val cond = ConditionActivity.resultFrom(result.data) ?: return@registerForActivityResult
        val base = draft ?: return@registerForActivityResult
        draft = applyCondition(base, cond)
        zoneName = cond.locationZoneName
        renderCondition()
        renderInterval()
        renderLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAppRuleBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        b.toolbarRule.setNavigationOnClickListener { finish() }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()

        buildDayChips()
        b.swFullDay.setOnCheckedChangeListener { _, checked ->
            draft = draft?.copy(fullDay = checked)
            b.btnIntervalStart.isEnabled = !checked
            b.btnIntervalEnd.isEnabled = !checked
        }
        b.btnIntervalStart.setOnClickListener { pickTime(draft?.intervalStartMin ?: 0) { draft = draft?.copy(intervalStartMin = it); renderInterval() } }
        b.btnIntervalEnd.setOnClickListener { pickTime(draft?.intervalEndMin ?: 0) { draft = draft?.copy(intervalEndMin = it); renderInterval() } }
        b.btnSetLocation.setOnClickListener { openCondition() }
        b.btnCondition.setOnClickListener { openCondition() }
        b.swBlockUnavailable.setOnCheckedChangeListener { _, checked ->
            draft = draft?.copy(blockIfLocationUnavailable = checked)
        }
        b.swBlockLaunch.setOnCheckedChangeListener { _, checked ->
            draft = draft?.copy(blockLaunch = checked)
        }
        b.swBlockNotifications.setOnCheckedChangeListener { _, checked ->
            draft = draft?.copy(blockNotifications = checked)
        }

        b.sbLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                b.tvLimitValue.text = getString(R.string.app_rule_limit_value, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        b.btnSaveRule.setOnClickListener {
            val current = item ?: return@setOnClickListener
            val mode = when (b.rgRuleMode.checkedRadioButtonId) {
                R.id.rbModeDuring -> Mode.DURING
                R.id.rbModeAllow -> Mode.ALLOW
                else -> Mode.BLOCK
            }
            val rule = (draft ?: BlockedApplication(current.packageName, current.label))
                .copy(blockDuringLock = mode == Mode.DURING, dailyLimitMinutes = b.sbLimit.progress)
            lifecycleScope.launch {
                val ok = vm.saveRule(
                    item = current,
                    rule = rule,
                    block = mode != Mode.ALLOW,
                    allowed = mode == Mode.ALLOW
                )
                if (ok) finish()
                else Toast.makeText(this@AppRuleActivity, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.items.collect { items ->
                    val found = items.firstOrNull { it.packageName == packageName }
                    if (found != null && found != item) render(found)
                }
            }
        }
    }

    private fun openCondition() {
        val d = draft ?: return
        conditionLauncher.launch(ConditionActivity.intentFor(this, d))
    }

    private fun applyCondition(base: BlockedApplication, cond: ConditionResult): BlockedApplication {
        if (cond.conditionType == "NONE") {
            // Clearing the condition resets every condition value, but keeps the launch/notification
            // switches and the lock mode fields.
            return base.copy(
                conditionType = "NONE", dailyLimitMinutes = 0, hourlyLimitMinutes = 0,
                intervalDays = 0, intervalStartMin = 0, intervalEndMin = 0, fullDay = false,
                quickBlockUntilEpochMs = 0, maxLaunchesPerDay = 0, maxLaunchesPerHour = 0,
                waitAfterMinutes = 0, waitForMinutes = 0, waitUntilEpochMs = 0,
                goalAppPackage = null, goalMinutes = 0, locationZoneId = 0, locationMode = "AT",
                blockIfLocationUnavailable = false
            )
        }
        return base.copy(
            conditionType = cond.conditionType,
            dailyLimitMinutes = cond.dailyLimitMinutes,
            hourlyLimitMinutes = cond.hourlyLimitMinutes,
            intervalDays = cond.intervalDays,
            intervalStartMin = cond.intervalStartMin,
            intervalEndMin = cond.intervalEndMin,
            fullDay = cond.fullDay,
            quickBlockUntilEpochMs = cond.quickBlockUntilEpochMs,
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

    private fun render(app: AppItem) {
        item = app
        draft = app.rule ?: BlockedApplication(app.packageName, app.label)
        b.ivRuleIcon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                ?: getDrawable(R.drawable.ic_nav_shield)
        )
        b.tvRuleLabel.text = app.label
        b.tvRulePackage.text = app.packageName
        // The "Today's Usage" label is a separate view; here only the value.
        b.tvRuleUsage.text = DurationFormat.compact(app.usageTodayMs)

        // Badge: Blocked (red) / Allowed (green) / no rule yet (grey).
        val (badgeText, badgeAttr) = when {
            app.alwaysAvailable -> R.string.app_state_always to R.attr.plTextSecondary
            app.rule != null -> R.string.app_rule_blocked_badge to R.attr.plDanger
            app.allowed -> R.string.app_rule_allowed_badge to R.attr.plSuccess
            else -> R.string.app_rule_no_rule to R.attr.plTextSecondary
        }
        val badgeColor = MaterialColors.getColor(b.root, badgeAttr, 0)
        b.tvRuleBadge.setText(badgeText)
        b.tvRuleBadge.setTextColor(badgeColor)
        b.tvRuleBadge.compoundDrawableTintList = ColorStateList.valueOf(badgeColor)

        // Radio preselection from the stored rule.
        b.rgRuleMode.check(
            when {
                app.allowed -> R.id.rbModeAllow
                app.rule != null && app.rule.blockDuringLock -> R.id.rbModeDuring
                app.rule != null -> R.id.rbModeBlock
                else -> R.id.rbModeDuring
            }
        )
        val limit = app.rule?.dailyLimitMinutes ?: 0
        b.sbLimit.progress = limit
        b.tvLimitValue.text = getString(R.string.app_rule_limit_value, limit)
        b.tvLimitHint.setText(
            if (Graph.usage.hasAccess()) R.string.rule_limit_hint else R.string.rule_limit_needs_access
        )

        val rule = draft!!
        b.swBlockLaunch.isChecked = rule.blockLaunch
        b.swBlockNotifications.isChecked = rule.blockNotifications
        b.swFullDay.isChecked = rule.fullDay
        b.btnIntervalStart.isEnabled = !rule.fullDay
        b.btnIntervalEnd.isEnabled = !rule.fullDay
        b.swBlockUnavailable.isChecked = rule.blockIfLocationUnavailable
        syncDayChips(rule.intervalDays)
        renderCondition()
        renderInterval()
        loadZoneName(rule.locationZoneId)
        renderLocation()

        if (app.alwaysAvailable) {
            b.tvRuleNote.isVisible = true
            b.rgRuleMode.isEnabled = false
            b.rbModeBlock.isEnabled = false
            b.rbModeDuring.isEnabled = false
            b.rbModeAllow.isEnabled = false
            b.sbLimit.isEnabled = false
            b.btnCondition.isEnabled = false
            b.swBlockLaunch.isEnabled = false
            b.swBlockNotifications.isEnabled = false
            b.swFullDay.isEnabled = false
            b.btnIntervalStart.isEnabled = false
            b.btnIntervalEnd.isEnabled = false
            b.btnSetLocation.isEnabled = false
            b.swBlockUnavailable.isEnabled = false
        }
    }

    // ------------------------------------------------------------------ days

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
            chip.setOnCheckedChangeListener { _, checked ->
                val d = draft ?: return@setOnCheckedChangeListener
                val days = if (checked) d.intervalDays or bits[i] else d.intervalDays and bits[i].inv()
                draft = d.copy(intervalDays = days)
            }
            b.chipGroupDays.addView(chip)
        }
    }

    private fun syncDayChips(days: Int) {
        val bits = listOf(
            ConditionEvaluator.DAY_MON, ConditionEvaluator.DAY_TUE, ConditionEvaluator.DAY_WED,
            ConditionEvaluator.DAY_THU, ConditionEvaluator.DAY_FRI, ConditionEvaluator.DAY_SAT,
            ConditionEvaluator.DAY_SUN
        )
        for (i in 0 until b.chipGroupDays.childCount) {
            (b.chipGroupDays.getChildAt(i) as? Chip)?.isChecked = days and bits[i] != 0
        }
    }

    // ------------------------------------------------------------------ intervals / location / condition

    private fun pickTime(currentMin: Int, onPicked: (Int) -> Unit) {
        val hour = (currentMin / 60).coerceIn(0, 23)
        val minute = (currentMin % 60).coerceIn(0, 59)
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, hour, minute, true).show()
    }

    private fun formatMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun renderInterval() {
        val rule = draft ?: return
        b.btnIntervalStart.text = getString(R.string.rule_interval_start_value, formatMin(rule.intervalStartMin))
        b.btnIntervalEnd.text = getString(R.string.rule_interval_end_value, formatMin(rule.intervalEndMin))
    }

    private fun loadZoneName(zoneId: Long) {
        if (zoneId <= 0) {
            zoneName = null
            renderLocation()
            return
        }
        lifecycleScope.launch {
            zoneName = Graph.db.safeZoneDao().get(zoneId)?.name
            renderLocation()
        }
    }

    private fun renderLocation() {
        val rule = draft ?: return
        b.btnSetLocation.text = zoneName ?: getString(R.string.rule_tap_set_location)
        b.swBlockUnavailable.isChecked = rule.blockIfLocationUnavailable
    }

    private fun renderCondition() {
        b.btnCondition.text = draft?.let { conditionSummary(it) } ?: getString(R.string.rule_condition_none)
    }

    private fun conditionSummary(rule: BlockedApplication): String = when (rule.conditionType) {
        "USAGE_LIMIT" -> when {
            rule.dailyLimitMinutes > 0 && rule.hourlyLimitMinutes > 0 ->
                getString(R.string.rule_cond_summary_usage_both, rule.dailyLimitMinutes, rule.hourlyLimitMinutes)
            rule.dailyLimitMinutes > 0 ->
                getString(R.string.rule_cond_summary_usage_daily, rule.dailyLimitMinutes)
            rule.hourlyLimitMinutes > 0 ->
                getString(R.string.rule_cond_summary_usage_hourly, rule.hourlyLimitMinutes)
            else -> getString(R.string.cond_usage_limit)
        }
        "TIME_INTERVAL" -> {
            val days = daysText(rule.intervalDays)
            if (rule.fullDay) getString(R.string.rule_cond_summary_fullday, days)
            else getString(
                R.string.rule_cond_summary_interval, days,
                formatMin(rule.intervalStartMin), formatMin(rule.intervalEndMin)
            )
        }
        "QUICK_BLOCK" -> {
            val until = Instant.ofEpochMilli(rule.quickBlockUntilEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            getString(R.string.rule_cond_summary_quick, until)
        }
        "LAUNCH_LIMIT" -> when {
            rule.maxLaunchesPerDay > 0 && rule.maxLaunchesPerHour > 0 ->
                getString(R.string.rule_cond_summary_launches_both, rule.maxLaunchesPerDay, rule.maxLaunchesPerHour)
            rule.maxLaunchesPerDay > 0 ->
                getString(R.string.rule_cond_summary_launches_day, rule.maxLaunchesPerDay)
            else -> getString(R.string.rule_cond_summary_launches_hour, rule.maxLaunchesPerHour)
        }
        "WAIT_TIMER" -> getString(R.string.rule_cond_summary_wait, rule.waitForMinutes, rule.waitAfterMinutes)
        "GOAL" -> getString(R.string.rule_cond_summary_goal, rule.goalMinutes, rule.goalAppPackage ?: "")
        "LOCATION" -> getString(
            R.string.rule_cond_summary_location,
            zoneName ?: getString(R.string.rule_tap_set_location),
            if (rule.locationMode == "AWAY") getString(R.string.cond_location_away) else getString(R.string.cond_location_at)
        )
        else -> getString(R.string.rule_condition_none)
    }

    private fun daysText(mask: Int): String {
        if (mask == 0) return getString(R.string.rule_cond_every_day)
        val labels = listOf("M", "T", "W", "T", "F", "S", "S")
        val bits = listOf(
            ConditionEvaluator.DAY_MON, ConditionEvaluator.DAY_TUE, ConditionEvaluator.DAY_WED,
            ConditionEvaluator.DAY_THU, ConditionEvaluator.DAY_FRI, ConditionEvaluator.DAY_SAT,
            ConditionEvaluator.DAY_SUN
        )
        return labels.filterIndexed { i, _ -> mask and bits[i] != 0 }.joinToString(" ")
    }

    private enum class Mode { BLOCK, DURING, ALLOW }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
