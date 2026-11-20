package com.purelock.app.ui.dashboard

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.databinding.FragmentDashboardBinding
import com.purelock.app.databinding.ItemSfProfileBinding
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.apps.AppListActivity
import com.purelock.app.ui.breaks.TakeBreakActivity
import com.purelock.app.ui.content.KeywordActivity
import com.purelock.app.ui.content.ReelsActivity
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Dashboard (SPEC S01): gradient header (hamburger opens the drawer, bell opens
 * notifications), time-of-day greeting, pink permission banner + Accessibility
 * Enable card (both hidden once accessibility is granted), Analytics 2-card row
 * with real totals and deltas, Take a Break card, Normal Mode card, blue Quick
 * Actions stack, Profiles (cards + ⋮ menu) and Profile Templates.
 *
 * Navigation targets that belong to other areas and are not delivered yet
 * (CreateProfileActivity, AllSitesActivity, AdultContentActivity,
 * StrictAlarmActivity, NotificationsActivity) are launched by [ComponentName];
 * if the class is still missing the user gets a "not ready yet" toast instead of
 * a crash. Sections hide per the Organise Dashboard flags (see
 * [DashboardViewModel]).
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val b get() = _binding!!
    private val vm: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setGreeting()
        wireClicks()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------------ render

    private fun render(state: DashboardViewModel.DashboardState) {
        val sections = state.visibleSections
        val bannerVisible = !state.accessibilityOn
        b.cardBanner.isVisible = bannerVisible
        b.cardAccessibility.isVisible = bannerVisible

        b.rowAnalyticsHeading.isVisible = sections.contains("analytics")
        b.cardScreenTime.isVisible = sections.contains("analytics")
        b.cardLaunches.isVisible = sections.contains("analytics")
        b.tvScreenTimeValue.text = state.screenTimeText
        b.tvScreenTimeDelta.text = state.screenTimeDeltaText
        b.tvLaunchesValue.text = state.launchesText
        b.tvLaunchesDelta.text = state.launchesDeltaText

        b.cardTakeBreak.isVisible = sections.contains("take_break")
        b.cardNormalMode.isVisible = sections.contains("mode")
        renderModeCard(state.modeLevel)

        b.tvQuickActions.isVisible = sections.contains("quick_actions")
        b.llQuickActions.isVisible = sections.contains("quick_actions")
        b.tvAppsBlockedCount.text = state.appBlocksToday.toString()
        b.tvSitesBlockedCount.text = state.websiteBlocksToday.toString()
        b.tvStrictAlarmCount.text = state.strictAlarmCount.toString()
        b.tvKeywordsState.text = getString(if (state.keywordsEnabled) R.string.sf_dash_on else R.string.sf_dash_off)
        b.tvAdultState.text = getString(if (state.adultEnabled) R.string.sf_dash_on else R.string.sf_dash_off)
        b.tvReelsState.text = getString(if (state.reelsEnabled) R.string.sf_dash_on else R.string.sf_dash_off)
        setSwitchSilently(b.switchKeywords, state.keywordsEnabled)
        setSwitchSilently(b.switchAdult, state.adultEnabled)
        setSwitchSilently(b.switchReels, state.reelsEnabled)

        val profilesVisible = sections.contains("profiles")
        b.tvProfiles.isVisible = profilesVisible
        b.btnAddProfile.isVisible = profilesVisible
        b.llProfiles.isVisible = profilesVisible
        if (profilesVisible) renderProfiles(state.profiles)

        val templatesVisible = sections.contains("templates")
        b.tvTemplates.isVisible = templatesVisible
        b.tvTemplatesSub.isVisible = templatesVisible
        b.scrollTemplates.isVisible = templatesVisible
    }

    /** Normal = 1 red lock, Lock = 2, Strict = 3 (SPEC S01 §8 / S04). */
    private fun renderModeCard(level: DashboardViewModel.ModeLevel) {
        val redLocks = when (level) {
            DashboardViewModel.ModeLevel.NORMAL -> 1
            DashboardViewModel.ModeLevel.LOCK -> 2
            DashboardViewModel.ModeLevel.STRICT -> 3
        }
        b.ivLock1.setImageResource(if (redLocks >= 1) R.drawable.ic_sf_lock_filled else R.drawable.ic_sf_lock_outline)
        b.ivLock2.setImageResource(if (redLocks >= 2) R.drawable.ic_sf_lock_filled else R.drawable.ic_sf_lock_outline)
        b.ivLock3.setImageResource(if (redLocks >= 3) R.drawable.ic_sf_lock_filled else R.drawable.ic_sf_lock_outline)
        // The card always shows the *current* mode's name; only Normal is described in S01.
        when (level) {
            DashboardViewModel.ModeLevel.NORMAL -> {
                b.tvModeTitle.setText(R.string.sf_dash_normal_mode)
                b.tvModeDesc.setText(R.string.sf_dash_normal_mode_desc)
            }
            DashboardViewModel.ModeLevel.LOCK -> {
                b.tvModeTitle.setText(R.string.sf_dash_lock_mode)
                b.tvModeDesc.setText(R.string.sf_dash_lock_mode_desc)
            }
            DashboardViewModel.ModeLevel.STRICT -> {
                b.tvModeTitle.setText(R.string.sf_dash_strict_mode)
                b.tvModeDesc.setText(R.string.sf_dash_strict_mode_desc)
            }
        }
    }

    private fun renderProfiles(profiles: List<FocusProfile>) {
        b.llProfiles.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val pm = requireContext().packageManager
        for (profile in profiles) {
            val item = ItemSfProfileBinding.inflate(inflater, b.llProfiles, false)
            item.tvProfileName.text = profile.name
            item.tvProfileActive.isVisible = profile.active && !profile.paused
            // Blocked-app icons row (up to 5).
            item.llProfileApps.removeAllViews()
            for (pkg in profile.appPackageList().take(5)) {
                val icon = ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                        marginEnd = 8
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(
                        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                            ?: runCatching { pm.getApplicationIcon(requireContext().packageName) }.getOrNull()
                    )
                }
                item.llProfileApps.addView(icon)
            }
            if (profile.appPackageList().isEmpty()) {
                item.llProfileApps.addView(TextView(requireContext()).apply {
                    text = profile.type
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.sf_muted))
                    textSize = 14f
                })
            }
            // Bottom row: schedule summary when active, Activate when inactive.
            val summary = scheduleSummary(profile)
            if (profile.active) {
                item.tvProfileSchedule.text = summary.ifEmpty { getString(R.string.sf_dash_active) }
                item.tvProfileActivate.isVisible = false
            } else {
                item.tvProfileSchedule.text = summary
                item.tvProfileSchedule.isVisible = summary.isNotEmpty()
                item.ivScheduleIcon.isVisible = summary.isNotEmpty()
                item.tvProfileActivate.isVisible = true
            }
            item.tvProfileActivate.setOnClickListener {
                vm.activateProfile(profile)
                Toast.makeText(requireContext(), R.string.sf_dash_profile_activated, Toast.LENGTH_SHORT).show()
            }
            item.root.setOnClickListener { openCreateProfile(profile.id) }
            item.btnProfileMenu.setOnClickListener { anchor -> showProfileMenu(anchor, profile) }
            b.llProfiles.addView(item.root)
        }
    }

    /** "01 mins daily" style summary; empty when the profile carries no schedule. */
    private fun scheduleSummary(profile: FocusProfile): String = when {
        profile.dailyLimitMinutes > 0 ->
            getString(R.string.sf_dash_mins_daily, profile.dailyLimitMinutes)
        profile.maxLaunchesPerDay > 0 ->
            getString(R.string.sf_dash_launches_daily, profile.maxLaunchesPerDay)
        profile.quickBlockMinutes > 0 ->
            getString(R.string.sf_dash_quick_block_mins, profile.quickBlockMinutes)
        else -> ""
    }

    private fun showProfileMenu(anchor: View, profile: FocusProfile) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(getString(R.string.sf_dash_menu_edit))
            menu.add(
                if (profile.paused) getString(R.string.sf_dash_menu_resume)
                else getString(R.string.sf_dash_menu_pause)
            )
            menu.add(getString(R.string.sf_dash_menu_delete))
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    getString(R.string.sf_dash_menu_edit) -> openCreateProfile(profile.id)
                    getString(R.string.sf_dash_menu_pause) ->
                        vm.setProfilePaused(profile, true)
                    getString(R.string.sf_dash_menu_resume) ->
                        vm.setProfilePaused(profile, false)
                    getString(R.string.sf_dash_menu_delete) -> confirmDelete(profile)
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(profile: FocusProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sf_dash_delete_title)
            .setMessage(getString(R.string.sf_dash_delete_msg, profile.name))
            .setPositiveButton(R.string.sf_dash_menu_delete) { _, _ ->
                vm.deleteProfile(profile)
                Toast.makeText(requireContext(), R.string.sf_dash_profile_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.sf_dash_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ clicks

    private fun setGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        b.tvGreeting.setText(
            when (hour) {
                in 0..11 -> R.string.sf_good_morning
                in 12..16 -> R.string.sf_good_afternoon
                else -> R.string.sf_good_evening
            }
        )
    }

    private fun wireClicks() {
        val main = requireActivity() as MainActivity
        b.btnMenu.setOnClickListener { main.openDrawer() }
        b.btnBell.setOnClickListener { openByClassName(NOTIFICATIONS_ACTIVITY) }
        b.btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // Analytics cards -> Analytics tab. (StatsFragment takes no initial-tab
        // argument yet, so both land on Screen Time; Area E can add the arg.)
        b.cardScreenTime.setOnClickListener { main.openTab(R.id.nav_analytics) }
        b.cardLaunches.setOnClickListener { main.openTab(R.id.nav_analytics) }

        b.cardTakeBreak.setOnClickListener { openTakeBreak(TakeBreakActivity.DEFAULT_DURATION_MS) }
        b.chipBreak5.setOnClickListener { openTakeBreak(5L * 60_000L) }
        b.chipBreak10.setOnClickListener { openTakeBreak(10L * 60_000L) }
        b.chipBreak15.setOnClickListener { openTakeBreak(15L * 60_000L) }
        b.chipBreakDay.setOnClickListener { openTakeBreak(24L * 3_600_000L) }

        b.cardNormalMode.setOnClickListener { main.openTab(R.id.nav_modes) }

        b.rowAppsBlocked.setOnClickListener {
            startActivity(Intent(requireContext(), AppListActivity::class.java))
        }
        b.rowSitesBlocked.setOnClickListener { openByClassName(ALL_SITES_ACTIVITY) }
        b.rowKeywords.setOnClickListener {
            startActivity(Intent(requireContext(), KeywordActivity::class.java))
        }
        b.tvConfigureKeywords.setOnClickListener {
            startActivity(Intent(requireContext(), KeywordActivity::class.java))
        }
        b.rowAdult.setOnClickListener { openByClassName(ADULT_CONTENT_ACTIVITY) }
        b.tvConfigureAdult.setOnClickListener { openByClassName(ADULT_CONTENT_ACTIVITY) }
        b.rowReels.setOnClickListener {
            startActivity(Intent(requireContext(), ReelsActivity::class.java))
        }
        b.tvConfigureReels.setOnClickListener {
            startActivity(Intent(requireContext(), ReelsActivity::class.java))
        }
        b.rowStrictAlarm.setOnClickListener { openByClassName(STRICT_ALARM_ACTIVITY) }

        // Toggles flip the feature without opening its screen. Click listeners
        // (not checked-change) so programmatic updates from render() never write back.
        b.switchKeywords.setOnClickListener { vm.setKeywordsEnabled(b.switchKeywords.isChecked) }
        b.switchAdult.setOnClickListener { vm.setAdultEnabled(b.switchAdult.isChecked) }
        b.switchReels.setOnClickListener { vm.setReelsEnabled(b.switchReels.isChecked) }

        b.btnAddProfile.setOnClickListener { openCreateProfile(null) }
        b.cardTemplateFocus.setOnClickListener { openCreateProfile(null, "FOCUS_HOURS") }
        b.cardTemplateSocial.setOnClickListener { openCreateProfile(null, "SOCIAL_LIMIT") }
    }

    private fun openTakeBreak(durationMs: Long) {
        startActivity(
            Intent(requireContext(), TakeBreakActivity::class.java)
                .putExtra(TakeBreakActivity.EXTRA_DURATION_MS, durationMs)
        )
    }

    private fun openCreateProfile(profileId: Long?, template: String? = null) {
        openByClassName(CREATE_PROFILE_ACTIVITY) {
            profileId?.let { putExtra("profile_id", it) }
            template?.let { putExtra("template", it) }
        }
    }

    /**
     * Launches an activity owned by another area by class name (it may not exist
     * yet — those activities are registered in the manifest but not delivered).
     * Falls back to a toast instead of crashing.
     */
    private fun openByClassName(className: String, configure: Intent.() -> Unit = {}) {
        try {
            val intent = Intent().setComponent(
                ComponentName(requireContext(), className)
            ).apply(configure)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.sf_dash_coming_soon, Toast.LENGTH_SHORT).show()
        }
    }

    /** Sets a switch without firing its OnCheckedChangeListener (avoids write loops). */
    private fun setSwitchSilently(
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        checked: Boolean
    ) {
        if (switch.isChecked != checked) switch.isChecked = checked
    }

    companion object {
        private const val CREATE_PROFILE_ACTIVITY = "com.purelock.app.ui.profiles.CreateProfileActivity"
        private const val ALL_SITES_ACTIVITY = "com.purelock.app.ui.apps.AllSitesActivity"
        private const val ADULT_CONTENT_ACTIVITY = "com.purelock.app.ui.content.AdultContentActivity"
        private const val STRICT_ALARM_ACTIVITY = "com.purelock.app.ui.alarm.StrictAlarmActivity"
        private const val NOTIFICATIONS_ACTIVITY = "com.purelock.app.ui.account.NotificationsActivity"
    }
}
