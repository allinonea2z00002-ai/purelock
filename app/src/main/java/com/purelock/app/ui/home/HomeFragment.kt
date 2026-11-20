package com.purelock.app.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.purelock.app.R
import com.purelock.app.databinding.FragmentHomeBinding
import com.purelock.app.domain.CombineRule
import com.purelock.app.domain.LockMode
import com.purelock.app.security.Issue
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.UiAnim
import com.purelock.app.ui.apps.AppListActivity
import com.purelock.app.util.DurationFormat
import com.purelock.app.util.Motivation
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val b get() = _binding!!
    private val vm: HomeViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)

        b.tvMotivation.text = Motivation.daily(requireContext())
        UiAnim.cardEntrance(listOf(b.cardRing, b.cardStatus, b.cardMotivation, b.quickRow1, b.quickRow2))

        val main = requireActivity() as MainActivity
        mapOf(
            b.cardLockTime to { main.openLockMode(LockMode.TIME) },
            b.cardLockDistance to { main.openLockMode(LockMode.DISTANCE) },
            b.cardLockCombined to { main.openLockMode(LockMode.COMBINED) },
            b.cardLockZones to { main.openTab(R.id.nav_map) },
            b.qaWebsites to { main.openProtectionTab("website") },
            b.qaApps to { startActivity(Intent(requireContext(), AppListActivity::class.java)) },
            b.qaStats to { main.openTab(R.id.nav_stats) },
            b.qaSettings to { main.openTab(R.id.nav_settings) }
        ).forEach { (card, action) ->
            UiAnim.pressEffect(card)
            card.setOnClickListener { UiAnim.haptic(it); action() }
        }
        b.btnMenu.setOnClickListener { main.openTab(R.id.nav_settings) }
        b.tvViewAll.setOnClickListener { main.openTab(R.id.nav_analysis) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state -> if (state != null) render(state) }
            }
        }
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(b.root, attr, Color.GRAY)

    private fun setRow(dot: View, value: android.widget.TextView, level: Level, text: String) {
        val c = when (level) {
            Level.OK -> color(R.attr.plSuccess)
            Level.WARN -> color(R.attr.plWarning)
            Level.BAD -> color(R.attr.plDanger)
            Level.OFF -> color(R.attr.plTextSecondary)
        }
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(c))
        value.text = text
        value.setTextColor(c)
    }

    private enum class Level { OK, WARN, BAD, OFF }

    private fun render(s: HomeState) {
        val snap = s.snapshot
        val broken = snap.active && !s.integrity.protectionIntact

        // --- status pill -------------------------------------------------------------------
        val pillColor = when {
            snap.active && broken -> color(R.attr.plWarning)
            snap.active -> color(R.attr.plSuccess)
            else -> color(R.attr.plTextSecondary)
        }
        b.tvStatusPill.text = getString(if (snap.active) R.string.home_status_active else R.string.home_status_inactive)
        b.tvStatusPill.setTextColor(pillColor)
        ViewCompat.setBackgroundTintList(
            b.tvStatusPill,
            ColorStateList.valueOf(
                if (snap.active) color(R.attr.plAccentSoft) else color(R.attr.plCardHigh)
            )
        )

        // --- ring ----------------------------------------------------------------------------
        val timeText: String
        val subText: String
        when {
            !snap.active -> {
                timeText = getString(R.string.ring_idle_time)
                subText = getString(R.string.ring_idle_sub)
            }
            snap.mode == LockMode.DISTANCE -> {
                timeText = DurationFormat.distance(snap.distanceM)
                subText = getString(R.string.ring_distance_of, DurationFormat.distance(snap.distanceLimitM.toFloat()))
            }
            else -> {
                timeText = DurationFormat.clock(snap.remainingMs)
                subText = if (snap.mode == LockMode.COMBINED) {
                    getString(if (snap.rule == CombineRule.ANY) R.string.ring_combined_any else R.string.ring_combined_all)
                } else getString(R.string.ring_remaining)
            }
        }
        b.tvLockedUntil.isVisible = snap.active
        b.ring.setState(snap.active, broken, snap.progress, timeText, subText)

        // --- status rows ---------------------------------------------------------------------
        val locationIssue = s.integrity.issues.any {
            it == Issue.LOCATION_PERMISSION || it == Issue.LOCATION_DISABLED ||
                it == Issue.BACKGROUND_LOCATION || it == Issue.LOCATION_STALE
        }
        if (snap.active && snap.mode != LockMode.TIME) {
            setRow(
                b.dotDistance, b.valDistance,
                if (locationIssue) Level.WARN else Level.OK,
                getString(
                    if (locationIssue) R.string.home_distance_interrupted else R.string.home_distance_value,
                    DurationFormat.distance(snap.distanceM),
                    DurationFormat.distance(snap.distanceLimitM.toFloat())
                )
            )
        } else {
            setRow(b.dotDistance, b.valDistance, Level.OFF, getString(R.string.state_off))
        }

        when {
            s.vpnRunning -> setRow(b.dotWeb, b.valWeb, Level.OK, getString(R.string.home_web_active))
            (snap.active && snap.blockAdult) || s.settings.filterEnabled ->
                setRow(b.dotWeb, b.valWeb, Level.BAD, getString(R.string.home_web_not_running))
            else -> setRow(b.dotWeb, b.valWeb, Level.OFF, getString(R.string.state_off))
        }

        when {
            snap.active && snap.blockApps && s.blockedAppCount > 0 ->
                if (s.accessibilityOn) {
                    setRow(b.dotApps, b.valApps, Level.OK, getString(R.string.home_apps_active, s.blockedAppCount))
                } else {
                    setRow(b.dotApps, b.valApps, Level.BAD, getString(R.string.home_apps_needs_accessibility))
                }
            else -> setRow(b.dotApps, b.valApps, Level.OFF, getString(R.string.state_off))
        }

        when {
            !snap.active -> setRow(b.dotBypass, b.valBypass, Level.OFF, getString(R.string.home_bypass_inactive))
            s.integrity.protectionIntact -> setRow(b.dotBypass, b.valBypass, Level.OK, getString(R.string.home_bypass_intact))
            else -> setRow(
                b.dotBypass, b.valBypass, Level.WARN,
                getString(R.string.home_bypass_attention, s.integrity.criticalIssues.size)
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
