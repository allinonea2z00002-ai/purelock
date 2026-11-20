package com.purelock.app.ui.protection

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.DevicePolicyHelper
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.databinding.DialogTextInputBinding
import com.purelock.app.databinding.FragmentProtectionBinding
import com.purelock.app.domain.CombineRule
import com.purelock.app.domain.LockEvaluator
import com.purelock.app.domain.LockManager
import com.purelock.app.domain.LockMode
import com.purelock.app.location.LocationHelper
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.UiAnim
import com.purelock.app.ui.apps.AppListActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ProtectionFragment : Fragment(R.layout.fragment_protection) {

    private var _binding: FragmentProtectionBinding? = null
    private val b get() = _binding!!
    private val vm: ProtectionViewModel by viewModels()

    // --- form selection -------------------------------------------------------------------------
    private var selectedTimeMs: Long = 24L * 3_600_000L
    private var customTime = false
    private var selectedDistanceM: Int = 5_000
    private var customDistance = false

    // --- start-lock flow state --------------------------------------------------------------------
    private class Pending(
        val mode: LockMode,
        val durationMs: Long,
        val distanceM: Int,
        val rule: CombineRule,
        val blockAdult: Boolean,
        var blockApps: Boolean
    ) {
        var bgDecided = false
        var appsDecided = false
        var startLat: Double? = null
        var startLng: Double? = null
        var confirmed = false
    }

    private var pending: Pending? = null

    private val locationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) continueStart()
            else {
                pending = null
                toast(R.string.err_location_denied)
            }
        }

    private val backgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pending ?: return@registerForActivityResult
            p.bgDecided = true
            if (granted) continueStart() else confirmWithoutBackground()
        }

    // =============================================================================================
    // setup
    // =============================================================================================

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentProtectionBinding.bind(view)

        UiAnim.cardEntrance(listOf(b.cardActive, b.cardForm, b.cardAdult, b.cardApps))
        UiAnim.pressEffect(b.btnStart)

        buildTimeChips()
        buildDistanceChips()
        setupModeToggles()
        setupFilterCard()
        setupAppsCard()
        setupTabs()

        // Deep link from Home (quick actions / lock-type cards): tab and/or mode.
        // A fragment argument Bundle key "tab" == "website" (sent by the caller that opens
        // this screen) also selects the Website tab; it wins over the MainActivity handoff.
        val main = requireActivity() as? MainActivity
        val pendingTab = main?.pendingProtectionTab
        val pendingMode = main?.pendingLockMode
        main?.pendingProtectionTab = null
        main?.pendingLockMode = null
        if (pendingMode != null) {
            b.toggleMode.check(
                when (pendingMode) {
                    LockMode.DISTANCE -> R.id.btnModeDistance
                    LockMode.COMBINED -> R.id.btnModeCombined
                    else -> R.id.btnModeTime
                }
            )
        }
        showTab(arguments?.getString("tab") ?: pendingTab ?: "smart")

        b.btnBackProt.setOnClickListener { (requireActivity() as MainActivity).openTab(R.id.nav_home) }
        b.btnStart.setOnClickListener { UiAnim.haptic(it); onStartClicked() }
        b.btnEmergency.setOnClickListener { showEmergencyDialog() }
        b.switchUninstall.setOnClickListener {
            vm.setUninstallProtection(b.switchUninstall.isChecked) {
                b.switchUninstall.isChecked = true
                toast(R.string.msg_locked_cannot_change)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.lock.collect { renderLock(it) } }
                launch { vm.settings.collect { renderSettings() } }
                launch { vm.blockedDomainCount.collect { renderFilterStatus() } }
                launch { vm.allowedDomainCount.collect { renderFilterStatus() } }
                launch { vm.lastUpdate.collect { renderFilterStatus() } }
                launch { vm.update.collect { renderUpdate(it) } }
                launch { vm.blockedAppCount.collect { renderAppsStatus() } }
                launch { vm.events.collect { onEvent(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have come back from a system settings page.
        if (_binding != null) {
            renderAppsStatus()
            renderFilterStatus()
        }
    }

    private fun setupTabs() {
        b.toggleTabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            showTab(
                when (id) {
                    R.id.btnTabWebsite -> "website"
                    R.id.btnTabApps -> "apps"
                    else -> "smart"
                }
            )
        }
    }

    /** Shows one of the three tabs ("smart" / "website" / "apps"). */
    fun showTab(tab: String) {
        b.toggleTabs.check(
            when (tab) {
                "website" -> R.id.btnTabWebsite
                "apps" -> R.id.btnTabApps
                else -> R.id.btnTabSmart
            }
        )
        b.tabSmart.isVisible = tab == "smart"
        b.tabWebsite.isVisible = tab == "website"
        b.tabApps.isVisible = tab == "apps"
    }

    private fun buildTimeChips() {
        val labels = LockEvaluator.TIME_PRESETS_MS.map { it to timeLabel(it) }
        for ((ms, label) in labels) {
            val chip = newChip(label, ms)
            b.chipsTime.addView(chip)
            if (ms == selectedTimeMs) chip.isChecked = true
        }
        b.chipsTime.addView(newChip(getString(R.string.chip_custom), CUSTOM_TAG))
        b.chipsTime.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val tag = chip.tag as Long
            customTime = tag == CUSTOM_TAG
            if (!customTime) selectedTimeMs = tag
            b.customTimeRow.isVisible = customTime
        }
    }

    private fun buildDistanceChips() {
        for (m in LockEvaluator.DISTANCE_PRESETS_M) {
            val chip = newChip(getString(R.string.chip_km, m / 1000), m.toLong())
            b.chipsDistance.addView(chip)
            if (m == selectedDistanceM) chip.isChecked = true
        }
        b.chipsDistance.addView(newChip(getString(R.string.chip_custom), CUSTOM_TAG))
        b.chipsDistance.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val tag = chip.tag as Long
            customDistance = tag == CUSTOM_TAG
            if (!customDistance) selectedDistanceM = tag.toInt()
            b.tilCustomKm.isVisible = customDistance
            b.tvCustomDistanceLabel.isVisible = customDistance
        }
    }

    private fun newChip(label: String, tag: Long): Chip {
        val chip = layoutInflater.inflate(R.layout.chip_choice, b.chipsTime, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.tag = tag
        return chip
    }

    private fun timeLabel(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> getString(R.string.chip_minutes, minutes.toInt())
            minutes < 1_440 -> getString(R.string.chip_hours, (minutes / 60).toInt())
            else -> getString(R.string.chip_days, (minutes / 1_440).toInt())
        }
    }

    private fun setupModeToggles() {
        fun updateMode() {
            val mode = currentMode()
            b.sectionTime.isVisible = mode.usesTime
            b.sectionDistance.isVisible = mode.usesDistance
            b.sectionRule.isVisible = mode == LockMode.COMBINED
            b.tvModeHelp.setText(
                when (mode) {
                    LockMode.TIME -> R.string.mode_time_help
                    LockMode.DISTANCE -> R.string.mode_distance_help
                    LockMode.COMBINED -> R.string.mode_combined_help
                }
            )
            b.tvRuleHelp.setText(if (currentRule() == CombineRule.ANY) R.string.rule_any_help else R.string.rule_all_help)
        }
        b.toggleMode.addOnButtonCheckedListener { _, _, _ -> updateMode(); UiAnim.haptic(b.toggleMode) }
        b.toggleRule.addOnButtonCheckedListener { _, _, _ -> updateMode(); UiAnim.haptic(b.toggleRule) }
        updateMode()
    }

    private fun currentMode(): LockMode = when (b.toggleMode.checkedButtonId) {
        R.id.btnModeDistance -> LockMode.DISTANCE
        R.id.btnModeCombined -> LockMode.COMBINED
        else -> LockMode.TIME
    }

    private fun currentRule(): CombineRule =
        if (b.toggleRule.checkedButtonId == R.id.btnRuleAll) CombineRule.ALL else CombineRule.ANY

    private fun setupFilterCard() {
        // The website-blocking toggle only saves FILTER_ENABLED. Enforcement is the
        // accessibility URL-watch plus the phone's Private DNS — no VPN consent any more.
        b.switchFilter.setOnClickListener {
            vm.setFilterEnabled(b.switchFilter.isChecked) {
                b.switchFilter.isChecked = true
                toast(R.string.msg_locked_cannot_change)
            }
        }
        b.switchSafeSearch.setOnClickListener {
            vm.setSafeSearch(b.switchSafeSearch.isChecked) {
                b.switchSafeSearch.isChecked = true
                toast(R.string.msg_locked_cannot_change)
            }
        }
        b.btnUpdateFilter.setOnClickListener { vm.updateFilterList() }
        b.btnBlacklist.setOnClickListener { DomainListSheet.newInstance(true).show(childFragmentManager, "blacklist") }
        b.btnAllowlist.setOnClickListener { DomainListSheet.newInstance(false).show(childFragmentManager, "allowlist") }
        b.btnPdnsCheck.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://check.kahfguard.com")))
            }
        }
        b.btnPdnsSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun setupAppsCard() {
        b.btnManageApps.setOnClickListener { startActivity(Intent(requireContext(), AppListActivity::class.java)) }
        b.btnA11y.setOnClickListener { showAccessibilityDisclosure(forStart = false) }
        b.btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        b.btnDeviceOwner.setOnClickListener { showDeviceOwnerGuide() }
    }

    // =============================================================================================
    // rendering (always from real state)
    // =============================================================================================

    private fun renderLock(cfg: LockConfiguration?) {
        val active = cfg?.active == true
        b.cardActive.isVisible = active
        b.cardForm.isVisible = !active
        if (active && cfg != null) {
            val mode = LockMode.fromName(cfg.mode)
            val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(cfg.startEpoch))
            val modeName = getString(
                when (mode) {
                    LockMode.TIME -> R.string.mode_time
                    LockMode.DISTANCE -> R.string.mode_distance
                    LockMode.COMBINED -> R.string.mode_combined
                }
            )
            val rule = if (mode == LockMode.COMBINED) {
                "\n" + getString(
                    if (CombineRule.fromName(cfg.combineRule) == CombineRule.ANY) R.string.rule_any_help else R.string.rule_all_help
                )
            } else ""
            b.tvActiveInfo.text = getString(R.string.prot_active_info, modeName, started) + rule
        }
    }

    private fun renderSettings() {
        val s = vm.settings.value
        b.switchFilter.isChecked = s.filterEnabled
        b.switchSafeSearch.isChecked = s.safeSearch
        b.switchUninstall.isChecked = s.uninstallProtection
        renderFilterStatus()
    }

    private fun renderFilterStatus() {
        if (_binding == null) return
        val s = vm.settings.value
        val sizes = getString(
            R.string.filter_counts,
            Graph.blocklist.listSize(), vm.blockedDomainCount.value, vm.allowedDomainCount.value
        )
        val status = if (s.filterEnabled) getString(R.string.web_filter_status_on)
            else getString(R.string.filter_status_off)
        val last = vm.lastUpdate.value?.let { (at, count) ->
            getString(
                R.string.filter_last_update,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)),
                count
            )
        } ?: getString(R.string.filter_never_updated)
        b.tvFilterStatus.text = "$status\n$sizes\n$last"
        b.cardSafe.isVisible = s.filterEnabled
        b.tvBlocklistCount.text = getString(R.string.web_domains_count, Graph.blocklist.listSize())
    }

    private fun renderUpdate(state: FilterUpdateState) {
        b.btnUpdateFilter.isEnabled = state != FilterUpdateState.Running
        b.tvFilterUpdate.text = when (state) {
            FilterUpdateState.Idle -> ""
            FilterUpdateState.Running -> getString(R.string.filter_updating)
            is FilterUpdateState.Done -> getString(R.string.filter_update_done, state.count)
            is FilterUpdateState.Failed -> getString(R.string.filter_update_failed, state.message)
        }
    }

    private fun renderAppsStatus() {
        if (_binding == null) return
        b.tvAppsSummary.text = resources.getQuantityString(
            R.plurals.apps_summary, vm.blockedAppCount.value, vm.blockedAppCount.value
        )
        val a11y = Graph.integrity.isAccessibilityEnabled()
        b.tvA11yStatus.setText(if (a11y) R.string.apps_a11y_on else R.string.apps_a11y_off)
        b.btnA11y.isVisible = !a11y

        val usage = Graph.usage.hasAccess()
        b.tvUsageStatus.setText(if (usage) R.string.apps_usage_on else R.string.apps_usage_off)
        b.btnUsage.isVisible = !usage

        b.tvDeviceOwner.setText(
            if (DevicePolicyHelper.isDeviceOwner(requireContext())) R.string.apps_owner_on else R.string.apps_owner_off
        )
    }

    // =============================================================================================
    // starting a lock
    // =============================================================================================

    private fun onStartClicked() {
        val mode = currentMode()

        var durationMs = 0L
        if (mode.usesTime) {
            durationMs = if (customTime) {
                val hours = b.etCustomHours.text?.toString()?.toLongOrNull() ?: 0L
                val minutes = b.etCustomMinutes.text?.toString()?.toLongOrNull() ?: 0L
                val total = hours * 60 + minutes
                if (!LockEvaluator.isValidCustomMinutes(total)) {
                    toast(R.string.err_custom_duration)
                    return
                }
                total * 60_000L
            } else selectedTimeMs
        }

        var distanceM = 0
        if (mode.usesDistance) {
            distanceM = if (customDistance) {
                val km = b.etCustomKm.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
                val meters = km?.times(1000)?.toInt() ?: -1
                if (!LockEvaluator.isValidDistance(meters)) {
                    toast(R.string.err_custom_distance)
                    return
                }
                meters
            } else selectedDistanceM
        }

        pending = Pending(
            mode = mode,
            durationMs = durationMs,
            distanceM = distanceM,
            rule = currentRule(),
            blockAdult = b.switchAdult.isChecked,
            blockApps = b.switchApps.isChecked
        )
        continueStart()
    }

    /** Runs the next unmet requirement; each prompt calls this again when it is done. */
    private fun continueStart() {
        val p = pending ?: return
        val ctx = requireContext()

        if (p.mode.usesDistance && !LocationHelper.hasForegroundPermission(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.perm_location_title)
                .setMessage(R.string.perm_location_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    locationPermissions.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
                .setOnCancelListener { pending = null }
                .show()
            return
        }

        if (p.mode.usesDistance && !LocationHelper.hasBackgroundPermission(ctx) && !p.bgDecided) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.perm_background_title)
                .setMessage(R.string.perm_background_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
                .setOnCancelListener { pending = null }
                .show()
            return
        }

        if (p.blockApps && !p.appsDecided && !Graph.integrity.isAccessibilityEnabled()) {
            p.appsDecided = true
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.a11y_disclosure_title)
                .setMessage(R.string.a11y_disclosure_message)
                .setPositiveButton(R.string.a11y_agree_open) { _, _ ->
                    pending = null
                    toast(R.string.a11y_after_enable)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton(R.string.a11y_continue_without) { _, _ ->
                    p.blockApps = false
                    continueStart()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
                .setOnCancelListener { pending = null }
                .show()
            return
        }

        if (p.mode.usesDistance && p.startLat == null) {
            fetchStartLocation(p)
            return
        }

        if (!p.confirmed) {
            confirmStart(p)
            return
        }

        vm.startLock(
            LockManager.StartRequest(
                mode = p.mode,
                durationMs = p.durationMs,
                distanceM = p.distanceM,
                rule = p.rule,
                startLat = p.startLat,
                startLng = p.startLng,
                blockAdult = p.blockAdult,
                blockApps = p.blockApps
            )
        )
        pending = null
    }

    private fun confirmWithoutBackground() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.perm_background_denied_title)
            .setMessage(R.string.perm_background_denied_message)
            .setPositiveButton(R.string.action_continue_anyway) { _, _ -> continueStart() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
            .setOnCancelListener { pending = null }
            .show()
    }

    private fun fetchStartLocation(p: Pending) {
        val ctx = requireContext()
        b.btnStart.isEnabled = false
        b.btnStart.setText(R.string.prot_getting_location)
        viewLifecycleOwner.lifecycleScope.launch {
            val fix = LocationHelper.currentFix(ctx, highAccuracy = true)
            if (_binding == null) return@launch
            b.btnStart.isEnabled = true
            b.btnStart.setText(R.string.ui_start_lock)
            if (fix == null) {
                pending = null
                toast(if (!LocationHelper.isLocationEnabled(ctx)) R.string.err_gps_disabled else R.string.err_no_fix)
            } else {
                p.startLat = fix.latitude
                p.startLng = fix.longitude
                continueStart()
            }
        }
    }

    private fun confirmStart(p: Pending) {
        val lines = ArrayList<String>()
        when (p.mode) {
            LockMode.TIME -> lines += getString(R.string.confirm_time, durationText(p.durationMs))
            LockMode.DISTANCE -> lines += getString(R.string.confirm_distance, p.distanceM / 1000.0)
            LockMode.COMBINED -> {
                lines += getString(R.string.confirm_time, durationText(p.durationMs))
                lines += getString(R.string.confirm_distance, p.distanceM / 1000.0)
                lines += getString(if (p.rule == CombineRule.ANY) R.string.rule_any_help else R.string.rule_all_help)
            }
        }
        if (p.blockAdult) lines += getString(R.string.confirm_adult)
        if (p.blockApps) lines += getString(R.string.confirm_apps)
        lines += getString(R.string.confirm_irreversible)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.confirm_start) { _, _ ->
                p.confirmed = true
                continueStart()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
            .setOnCancelListener { pending = null }
            .show()
    }

    private fun durationText(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> getString(R.string.chip_minutes, minutes.toInt())
            minutes < 1_440 && minutes % 60 == 0L -> getString(R.string.chip_hours, (minutes / 60).toInt())
            minutes % 1_440 == 0L -> getString(R.string.chip_days, (minutes / 1_440).toInt())
            else -> getString(R.string.duration_h_m, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    private fun onEvent(event: ProtectionEvent) {
        when (event) {
            is ProtectionEvent.StartResultEvent -> when (event.result) {
                LockManager.StartResult.Started -> {
                    UiAnim.hapticConfirm(b.btnStart)
                    toast(R.string.msg_lock_started)
                }
                LockManager.StartResult.AlreadyActive -> toast(R.string.msg_lock_already_active)
                LockManager.StartResult.InvalidDuration -> toast(R.string.err_custom_duration)
                LockManager.StartResult.InvalidDistance -> toast(R.string.err_custom_distance)
                LockManager.StartResult.NeedsStartLocation -> toast(R.string.err_no_fix)
            }
            ProtectionEvent.EmergencyDone -> {
                toast(R.string.msg_emergency_done)
                offerContactMessage()
            }
        }
    }

    // =============================================================================================
    // dialogs
    // =============================================================================================

    private fun showAccessibilityDisclosure(forStart: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.a11y_disclosure_title)
            .setMessage(R.string.a11y_disclosure_message)
            .setPositiveButton(R.string.a11y_agree_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeviceOwnerGuide() {
        val command = DevicePolicyHelper.provisioningCommand(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.owner_title)
            .setMessage(getString(R.string.owner_message, command))
            .setPositiveButton(R.string.action_copy_command) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("adb", command))
                toast(R.string.msg_copied)
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** Ends the lock after an explicit, typed confirmation. Always possible; always recorded. */
    private fun showEmergencyDialog() {
        val dialogBinding = DialogTextInputBinding.inflate(LayoutInflater.from(requireContext()))
        val word = getString(R.string.emergency_confirm_word)
        dialogBinding.tvDialogMessage.text = getString(R.string.emergency_message, word)
        dialogBinding.til.hint = word

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emergency_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.emergency_confirm_button) { _, _ ->
                val typed = dialogBinding.et.text?.toString().orEmpty().trim()
                if (typed.equals(word, ignoreCase = true)) vm.emergencyOverride()
                else toast(R.string.emergency_wrong_word)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** After an override, offer (never automatically send) a message to the trusted contact. */
    private fun offerContactMessage() {
        if (!Graph.secure.hasTrustedContact()) return
        val name = Graph.secure.trustedName.ifBlank { Graph.secure.trustedPhone }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.contact_offer_title)
            .setMessage(getString(R.string.contact_offer_message, name))
            .setPositiveButton(R.string.contact_offer_send) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Graph.secure.trustedPhone}"))
                    .putExtra("sms_body", getString(R.string.contact_sms_body))
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val CUSTOM_TAG = -1L
    }
}
