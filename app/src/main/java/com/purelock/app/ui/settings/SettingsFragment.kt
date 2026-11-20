package com.purelock.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.BuildConfig
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SecurityEvent
import com.purelock.app.data.prefs.UiPrefs
import com.purelock.app.databinding.FragmentSettingsBinding
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.UiAnim
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val b get() = _binding!!
    private val vm: SettingsViewModel by viewModels()

    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) vm.exportTo(uri)
        }
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) vm.importFrom(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)
        UiAnim.cardEntrance(listOf(b.cardAppearance, b.cardPrefs, b.cardContact, b.cardData, b.cardAbout))

        setupAppearance()
        setupPrefs()
        setupContact()
        setupData()
        b.tvAbout.text = getString(R.string.set_about_text, BuildConfig.VERSION_NAME)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.settings.collect { renderSwitches() } }
                launch {
                    vm.historyCount.collect { b.btnDeleteHistory.isEnabled = it > 0 }
                }
                launch { vm.messages.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
                launch { Graph.db.securityEventDao().observeRecent(10).collect { renderEvents(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) renderSystemStatus()
    }

    // ---------------------------------------------------------------------------------------------

    private fun setupAppearance() {
        val white = UiPrefs.theme(requireContext()) == UiPrefs.THEME_WHITE
        b.toggleTheme.check(if (white) R.id.btnThemeWhite else R.id.btnThemeDark)
        b.toggleTheme.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val theme = if (id == R.id.btnThemeWhite) UiPrefs.THEME_WHITE else UiPrefs.THEME_DARK
            UiAnim.haptic(b.toggleTheme)
            ThemeManager.switchTheme(requireActivity(), theme)
        }

        val bn = UiPrefs.language() == "bn"
        b.toggleLanguage.check(if (bn) R.id.btnLangBn else R.id.btnLangEn)
        b.toggleLanguage.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val tag = if (id == R.id.btnLangBn) "bn" else "en"
            if (tag != UiPrefs.language()) {
                UiAnim.haptic(b.toggleLanguage)
                UiPrefs.setLanguage(tag) // recreates the activity in the new language
            }
        }
    }

    private fun setupPrefs() {
        b.switchAlerts.setOnClickListener { vm.setInterruptionAlerts(b.switchAlerts.isChecked) }
        b.switchKeepFilter.setOnClickListener { vm.setKeepFilterAfterLock(b.switchKeepFilter.isChecked) }
        b.switchUninstall.setOnClickListener {
            vm.setUninstallProtection(b.switchUninstall.isChecked)
            // If the change was refused (a lock is active) the collector below restores the stored value.
        }
        b.btnNotifSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            )
        }
        b.btnBattery.setOnClickListener {
            // Shows the system list; the user decides. (No direct "ignore optimizations" prompt, which Play restricts.)
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun setupContact() {
        b.etContactName.setText(Graph.secure.trustedName)
        b.etContactPhone.setText(Graph.secure.trustedPhone)
        b.btnSaveContact.setOnClickListener {
            vm.saveTrustedContact(
                b.etContactName.text?.toString().orEmpty(),
                b.etContactPhone.text?.toString().orEmpty()
            )
        }
    }

    private fun setupData() {
        b.switchHistory.setOnClickListener { vm.setStoreLocationHistory(b.switchHistory.isChecked) }
        b.btnDeleteHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.loc_delete_history)
                .setMessage(R.string.loc_delete_confirm)
                .setPositiveButton(R.string.action_delete) { _, _ -> vm.deleteLocationHistory() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        b.btnExport.setOnClickListener { exportFile.launch("purelock-backup.json") }
        b.btnImport.setOnClickListener { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
        b.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_clear_all)
                .setMessage(R.string.set_clear_confirm)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    vm.clearAllData { (activity as? MainActivity)?.openTab(R.id.nav_home) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun renderSwitches() {
        val s = vm.settings.value
        b.switchAlerts.isChecked = s.interruptionAlerts
        b.switchKeepFilter.isChecked = s.keepFilterAfterLock
        b.switchUninstall.isChecked = s.uninstallProtection
        b.switchHistory.isChecked = s.storeLocationHistory
    }

    private fun renderSystemStatus() {
        val ctx = requireContext()
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        b.tvNotifStatus.setText(if (enabled) R.string.set_notif_on else R.string.set_notif_off)
        b.btnNotifSettings.isVisible = !enabled

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        b.tvBattery.setText(if (unrestricted) R.string.set_battery_ok else R.string.set_battery_restricted)
        b.btnBattery.isVisible = !unrestricted
    }

    private fun renderEvents(events: List<SecurityEvent>) {
        if (_binding == null) return
        if (events.isEmpty()) {
            b.tvEvents.setText(R.string.set_no_events)
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        b.tvEvents.text = events.joinToString("\n") { e ->
            "${format.format(Date(e.epoch))}  ${eventName(e.type)}"
        }
    }

    private fun eventName(type: String): String = getString(
        when (type) {
            "LOCK_STARTED" -> R.string.ev_lock_started
            "LOCK_COMPLETED" -> R.string.ev_lock_completed
            "EMERGENCY_OVERRIDE" -> R.string.ev_emergency
            "TIME_CHANGED" -> R.string.ev_time_changed
            "REBOOT_RECOVERY" -> R.string.ev_reboot
            "VPN_REVOKED" -> R.string.ev_vpn_revoked
            "INTEGRITY_ISSUE" -> R.string.ev_integrity_issue
            "INTEGRITY_RESTORED" -> R.string.ev_integrity_restored
            "GEOFENCE_ERROR" -> R.string.ev_geofence_error
            "ZONE_LOCK" -> R.string.ev_zone_lock
            else -> R.string.ev_other
        }
    )

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
