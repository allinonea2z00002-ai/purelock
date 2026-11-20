package com.purelock.app.ui.apps

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.R
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.databinding.ActivityAppListBinding
import com.purelock.app.databinding.ItemSfAppBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

/**
 * All Apps (SPEC S07): grey search field, "App Name" sort button + popup
 * (App Name / Blocked / Launches / Usage Time / Pinned), rows showing the app icon,
 * name and "Spent: … | … launches" (real UsageStats values via Graph.usage).
 *
 * Row tap opens the blocking-condition flow (S08): [ConditionActivity] prefilled with
 * that app's rule, then the schedule screen, then the rule is saved through
 * [AppListViewModel.saveRule]. The lock icon toggles block/unblock directly:
 * a blocked app is unblocked at once; an unblocked app opens the same flow.
 * Long-press a row to pin/unpin it (the "Pinned" sort order).
 *
 * Per-app "Always Allow" was removed by coordinator decision — the profiles'
 * Whitelist covers that use case — so the flow always saves with allowed = false.
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppListBinding
    private val vm: AppListViewModel by viewModels()
    private val adapter = AppAdapter(
        onRowClick = { openRuleFlow(it) },
        onLockClick = { toggleLock(it) },
        onLongClick = { togglePin(it) }
    )

    /** The app whose rule is being edited through Condition -> Schedule. */
    private var pendingItem: AppItem? = null
    /** Working copy of the rule, built from the ConditionActivity result. */
    private var pendingRule: BlockedApplication? = null
    /** Whether the EXTRA_PACKAGE deep link has been handled (once, after first load). */
    private var deepLinkHandled = false

    private val conditionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val item = pendingItem ?: return@registerForActivityResult
        val cond = ConditionActivity.resultFrom(result.data) ?: return@registerForActivityResult
        val base = item.rule ?: BlockedApplication(item.packageName, item.label)
        pendingRule = applyCondition(base, cond)
        openSchedule(item, pendingRule!!)
    }

    private val scheduleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val item = pendingItem ?: return@registerForActivityResult
        val draft = pendingRule ?: return@registerForActivityResult
        // ScheduleActivity returns the full rule through the same ConditionActivity
        // extras (see the contract in openSchedule). No extras = schedule confirmed,
        // keep the rule as chosen in the condition step. The I Want to Block
        // toggles ride as ScheduleActivity extras and are applied too.
        val finalRule = ConditionActivity.resultFrom(result.data)
            ?.let { applyCondition(draft, it) } ?: draft
        val blockLaunch = result.data?.getBooleanExtra(
            com.purelock.app.ui.profiles.ScheduleActivity.EXTRA_BLOCK_LAUNCH,
            finalRule.blockLaunch
        ) ?: finalRule.blockLaunch
        val blockNotifications = result.data?.getBooleanExtra(
            com.purelock.app.ui.profiles.ScheduleActivity.EXTRA_BLOCK_NOTIFICATIONS,
            finalRule.blockNotifications
        ) ?: finalRule.blockNotifications
        savePendingRule(
            item,
            finalRule.copy(blockLaunch = blockLaunch, blockNotifications = blockNotifications)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }
        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter
        b.etSearch.doAfterTextChanged { vm.setQuery(it?.toString().orEmpty()) }
        b.btnSort.setOnClickListener { showSortPopup() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.items.collect {
                        adapter.submitList(it)
                        b.tvEmpty.isVisible = it.isEmpty() && !vm.loading.value
                        // Deep link (e.g. Analytics "Block this app"): open the rule flow
                        // for the requested package once the app list is loaded.
                        if (!deepLinkHandled && it.isNotEmpty()) {
                            deepLinkHandled = true
                            intent.getStringExtra(EXTRA_PACKAGE)?.let { pkg ->
                                it.firstOrNull { app -> app.packageName == pkg }?.let { item ->
                                    openRuleFlow(item)
                                }
                            }
                        }
                    }
                }
                launch { vm.loading.collect { b.progress.isVisible = it } }
                launch {
                    vm.sortOrder.collect { order ->
                        b.tvSortLabel.setText(
                            when (order) {
                                AppSort.NAME -> R.string.sf_apps_sort_app_name
                                AppSort.BLOCKED -> R.string.sf_apps_sort_blocked
                                AppSort.LAUNCHES -> R.string.sf_apps_sort_launches
                                AppSort.USAGE -> R.string.sf_apps_sort_usage
                                AppSort.PINNED -> R.string.sf_apps_sort_pinned
                            }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sort

    private fun showSortPopup() {
        val popup = PopupMenu(this, b.btnSort)
        popup.menu.add(Menu.NONE, AppSort.NAME.ordinal, Menu.NONE, R.string.sf_apps_sort_app_name)
        popup.menu.add(Menu.NONE, AppSort.BLOCKED.ordinal, Menu.NONE, R.string.sf_apps_sort_blocked)
        popup.menu.add(Menu.NONE, AppSort.LAUNCHES.ordinal, Menu.NONE, R.string.sf_apps_sort_launches)
        popup.menu.add(Menu.NONE, AppSort.USAGE.ordinal, Menu.NONE, R.string.sf_apps_sort_usage)
        popup.menu.add(Menu.NONE, AppSort.PINNED.ordinal, Menu.NONE, R.string.sf_apps_sort_pinned)
        popup.setOnMenuItemClickListener { menuItem ->
            vm.setSort(AppSort.entries[menuItem.itemId])
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------ flow

    private fun openRuleFlow(item: AppItem) {
        if (item.alwaysAvailable) {
            Toast.makeText(this, R.string.sf_apps_always_available, Toast.LENGTH_SHORT).show()
            return
        }
        pendingItem = item
        pendingRule = null
        conditionLauncher.launch(ConditionActivity.intentFor(this, item.rule))
    }

    /**
     * Opens the schedule screen (S20/S21, owned by the profiles area).
     * Contract: ScheduleActivity receives the app + the full rule through the
     * [ConditionActivity] extras (plus [EXTRA_PACKAGE]/[EXTRA_LABEL]/[EXTRA_MODE]),
     * and on RESULT_OK returns the (possibly edited) rule through the same extras,
     * readable with [ConditionActivity.resultFrom]. Until ScheduleActivity is built,
     * the ActivityNotFoundException fallback below saves the rule as chosen.
     */
    private fun openSchedule(item: AppItem, rule: BlockedApplication) {
        val intent = Intent().apply {
            component = ComponentName(this@AppListActivity, SCHEDULE_ACTIVITY_CLASS)
            putExtra(EXTRA_MODE, MODE_APP_RULE)
            putExtra(EXTRA_PACKAGE, item.packageName)
            putExtra(EXTRA_LABEL, item.label)
            putRuleExtras(this, rule)
        }
        try {
            scheduleLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.sf_apps_schedule_unavailable, Toast.LENGTH_LONG).show()
            savePendingRule(item, rule)
        }
    }

    private fun savePendingRule(item: AppItem, rule: BlockedApplication) {
        lifecycleScope.launch {
            val ok = vm.saveRule(item = item, rule = rule, block = true, allowed = false)
            if (!ok) Toast.makeText(this@AppListActivity, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
            pendingItem = null
            pendingRule = null
        }
    }

    private fun toggleLock(item: AppItem) {
        if (item.alwaysAvailable) {
            Toast.makeText(this, R.string.sf_apps_always_available, Toast.LENGTH_SHORT).show()
            return
        }
        if (item.rule != null) {
            lifecycleScope.launch {
                val ok = vm.saveRule(item = item, rule = item.rule, block = false, allowed = false)
                if (!ok) Toast.makeText(this@AppListActivity, R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
            }
        } else {
            openRuleFlow(item)
        }
    }

    private fun togglePin(item: AppItem) {
        val pinned = vm.togglePin(item.packageName)
        Toast.makeText(
            this,
            if (pinned) R.string.sf_apps_pinned else R.string.sf_apps_unpinned,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Merges a [ConditionResult] into a rule (moved here from the deleted app-rule screen). */
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

    private fun putRuleExtras(intent: Intent, rule: BlockedApplication) {
        intent.putExtra(ConditionActivity.EXTRA_CONDITION_TYPE, rule.conditionType)
        intent.putExtra(ConditionActivity.EXTRA_DAILY_LIMIT, rule.dailyLimitMinutes)
        intent.putExtra(ConditionActivity.EXTRA_HOURLY_LIMIT, rule.hourlyLimitMinutes)
        intent.putExtra(ConditionActivity.EXTRA_INTERVAL_DAYS, rule.intervalDays)
        intent.putExtra(ConditionActivity.EXTRA_INTERVAL_START, rule.intervalStartMin)
        intent.putExtra(ConditionActivity.EXTRA_INTERVAL_END, rule.intervalEndMin)
        intent.putExtra(ConditionActivity.EXTRA_FULL_DAY, rule.fullDay)
        intent.putExtra(ConditionActivity.EXTRA_QUICK_UNTIL, rule.quickBlockUntilEpochMs)
        intent.putExtra(ConditionActivity.EXTRA_LAUNCHES_DAY, rule.maxLaunchesPerDay)
        intent.putExtra(ConditionActivity.EXTRA_LAUNCHES_HOUR, rule.maxLaunchesPerHour)
        intent.putExtra(ConditionActivity.EXTRA_WAIT_AFTER, rule.waitAfterMinutes)
        intent.putExtra(ConditionActivity.EXTRA_WAIT_FOR, rule.waitForMinutes)
        intent.putExtra(ConditionActivity.EXTRA_GOAL_PKG, rule.goalAppPackage)
        intent.putExtra(ConditionActivity.EXTRA_GOAL_MINUTES, rule.goalMinutes)
        intent.putExtra(ConditionActivity.EXTRA_ZONE_ID, rule.locationZoneId)
        intent.putExtra(ConditionActivity.EXTRA_LOCATION_MODE, rule.locationMode)
        intent.putExtra(ConditionActivity.EXTRA_BLOCK_UNAVAILABLE, rule.blockIfLocationUnavailable)
    }

    // ------------------------------------------------------------------ list

    private inner class AppAdapter(
        private val onRowClick: (AppItem) -> Unit,
        private val onLockClick: (AppItem) -> Unit,
        private val onLongClick: (AppItem) -> Unit
    ) : ListAdapter<AppItem, AppAdapter.Holder>(object : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppItem, b: AppItem) = a == b
    }) {

        inner class Holder(val binding: ItemSfAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSfAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val bind = holder.binding
            bind.tvLabel.text = item.label
            bind.ivIcon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(item.packageName) }.getOrNull()
            )
            bind.tvDetail.text = getString(
                R.string.sf_apps_app_spent,
                DurationFormat.formatMinsSecs(item.usageTodayMs),
                DurationFormat.formatLaunches(item.launchesToday)
            )
            val blocked = item.rule != null
            bind.btnLock.setImageResource(
                if (blocked) R.drawable.ic_sf_lock_closed else R.drawable.ic_sf_lock_open
            )
            bind.btnLock.imageTintList = ColorStateList.valueOf(
                getColor(if (blocked) R.color.sf_red else R.color.sf_text)
            )
            bind.btnLock.contentDescription = getString(
                if (blocked) R.string.sf_apps_cd_lock_blocked else R.string.sf_apps_cd_lock_unblocked
            )
            bind.btnLock.isEnabled = !item.alwaysAvailable
            bind.viewDivider.isVisible = position < itemCount - 1
            bind.root.setOnClickListener { onRowClick(item) }
            bind.root.setOnLongClickListener { onLongClick(item); true }
            bind.btnLock.setOnClickListener { onLockClick(item) }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MODE = "mode"
        const val MODE_APP_RULE = "app_rule"

        /** ScheduleActivity (profiles area) — referenced by class name so this compiles
         *  before that activity exists; see openSchedule(). */
        const val SCHEDULE_ACTIVITY_CLASS = "com.purelock.app.ui.profiles.ScheduleActivity"
    }
}
