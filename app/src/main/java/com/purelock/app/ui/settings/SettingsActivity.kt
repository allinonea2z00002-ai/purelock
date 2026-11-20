package com.purelock.app.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.data.prefs.UiPrefs
import com.purelock.app.databinding.ActivitySettingsBinding
import com.purelock.app.databinding.ItemSfSettingRowBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.apps.AppListActivity
import com.purelock.app.ui.drawer.RowBinder
import kotlinx.coroutines.launch

/**
 * Settings (SPEC S23): App Management / Customization / Blocking Options /
 * Time & Alerts / Notifications — exactly the rows shown in the screenshot,
 * no invented rows. Toggle rows persist to [SettingsStore] (the four Blocking
 * Options toggles share their keys with the Strict wizard's Extra Restrictions).
 *
 * Honest limits: the Pause flag, rotation flag, custom quote and redirect URL
 * are persisted here; the engine pieces that read them (accessibility service,
 * block screen, MainActivity orientation) are owned by other areas.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var current = AppSettings()

    /** Toggle rows, re-rendered from the settings flow (switch state = stored value). */
    private val toggles = mutableListOf<ToggleBinding>()

    private data class ToggleBinding(
        val row: ItemSfSettingRowBinding,
        val key: Preferences.Key<Boolean>,
        val get: (AppSettings) -> Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBackSettings.setOnClickListener { finish() }

        bindRows()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.settings.flow.collect { render(it) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun bindRows() {
        RowBinder.navRow(
            b.rowSetUninstall, this, R.drawable.ic_sf_trash,
            R.color.sf_pink, R.color.sf_red,
            R.string.sf_set_uninstall, R.string.sf_set_uninstall_desc
        ) { confirmUninstall() }
        RowBinder.navRow(
            b.rowSetPause, this, R.drawable.ic_sf_play,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_pause, R.string.sf_set_pause_desc
        ) { togglePause() }
        RowBinder.navRow(
            b.rowSetSyncHidden, this, R.drawable.ic_sf_folder_sync,
            R.color.sf_green_tint, R.color.sf_green,
            R.string.sf_set_sync_hidden, R.string.sf_set_sync_hidden_desc
        ) {
            // The app list is re-read from the package manager on open, which
            // picks up apps that were hidden from it before.
            startActivity(Intent(this, AppListActivity::class.java))
        }

        RowBinder.navRow(
            b.rowSetLauncher, this, R.drawable.ic_sf_rocket,
            R.color.sf_pill_active, R.color.sf_gradient_start,
            R.string.sf_set_launcher, R.string.sf_set_launcher_desc,
            R.string.sf_set_launcher_hint
        ) { openHomeSettings() }
        toggleRow(
            b.rowSetRotation, R.drawable.ic_sf_rotate,
            R.color.sf_peach, R.color.sf_warning,
            R.string.sf_set_rotation, R.string.sf_set_rotation_desc,
            SettingsKeys.ALLOW_HOME_ROTATION
        ) { it.allowHomeRotation }
        RowBinder.navRow(
            b.rowSetLanguage, this, R.drawable.ic_globe,
            R.color.sf_green_tint, R.color.sf_green,
            R.string.sf_set_language, R.string.sf_set_language_desc
        ) { languageDialog() }
        RowBinder.navRow(
            b.rowSetQuote, this, R.drawable.ic_sf_quote,
            R.color.sf_yellow, R.color.sf_warning,
            R.string.sf_set_quote, R.string.sf_set_quote_desc
        ) { quoteDialog() }
        RowBinder.navRow(
            b.rowSetRedirect, this, R.drawable.ic_sf_redirect,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_redirect, R.string.sf_set_redirect_desc
        ) { redirectDialog() }
        RowBinder.navRow(
            b.rowSetStartDay, this, R.drawable.ic_clock,
            R.color.sf_green_tint, R.color.sf_green,
            R.string.sf_set_start_day, R.string.sf_set_start_day_desc
        ) { startOfDayDialog() }
        toggleRow(
            b.rowSetPauseDuration, R.drawable.ic_sf_pause,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_pause_duration, R.string.sf_set_pause_duration_desc,
            SettingsKeys.ENABLE_PAUSE_DURATION
        ) { it.enablePauseDuration }

        toggleRow(
            b.rowSetBlockBrowsers, R.drawable.ic_sf_person_plus,
            R.color.sf_pink, R.color.sf_red,
            R.string.sf_set_block_browsers, R.string.sf_set_block_browsers_desc,
            SettingsKeys.BLOCK_UNSUPPORTED_BROWSERS
        ) { it.blockUnsupportedBrowsers }
        toggleRow(
            b.rowSetBlockSplit, R.drawable.ic_sf_split,
            R.color.sf_peach, R.color.sf_warning,
            R.string.sf_set_block_split, R.string.sf_set_block_split_desc,
            SettingsKeys.BLOCK_SPLIT_SCREEN
        ) { it.blockSplitScreen }
        toggleRow(
            b.rowSetBlockPower, R.drawable.ic_sf_power,
            R.color.sf_green_tint, R.color.sf_green,
            R.string.sf_set_block_power, R.string.sf_set_block_power_desc,
            SettingsKeys.BLOCK_POWER_OFF
        ) { it.blockPowerOff }
        toggleRow(
            b.rowSetBlockRecent, R.drawable.ic_sf_recent,
            R.color.sf_yellow, R.color.sf_warning,
            R.string.sf_set_block_recent, R.string.sf_set_block_recent_desc,
            SettingsKeys.BLOCK_RECENT_APPS
        ) { it.blockRecentApps }

        RowBinder.navRow(
            b.rowSetTimerType, this, R.drawable.ic_clock,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_timer_type, R.string.sf_set_timer_type_desc
        ) { timerTypeDialog() }
        toggleRow(
            b.rowSetLowTime, R.drawable.ic_sf_warning,
            R.color.sf_pink, R.color.sf_red,
            R.string.sf_set_low_time, R.string.sf_set_low_time_desc,
            SettingsKeys.LOW_TIME_ALERT
        ) { it.lowTimeAlert }

        toggleRow(
            b.rowSetPomodoro, R.drawable.ic_sf_bell,
            R.color.sf_pill_active, R.color.sf_gradient_start,
            R.string.sf_set_pomodoro, R.string.sf_set_pomodoro_desc,
            SettingsKeys.POMODORO_BREAK_NOTIFY
        ) { it.pomodoroBreakNotify }
    }

    private fun toggleRow(
        row: ItemSfSettingRowBinding,
        icon: Int,
        tintBg: Int,
        tintIcon: Int,
        title: Int,
        desc: Int,
        key: Preferences.Key<Boolean>,
        get: (AppSettings) -> Boolean
    ) {
        toggles += ToggleBinding(row, key, get)
        RowBinder.toggleRow(
            row, this, icon, tintBg, tintIcon, title, desc,
            initial = get(current)
        ) { checked ->
            lifecycleScope.launch { Graph.settings.set(key, checked) }
        }
    }

    private fun render(settings: AppSettings) {
        current = settings
        for (t in toggles) {
            // setChecked only fires the listener on a real change, so re-rendering
            // the stored value never loops back into a save.
            t.row.rowSwitch.isChecked = t.get(settings)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun confirmUninstall() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_uninstall_confirm)
            .setMessage(R.string.sf_set_uninstall_confirm_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun togglePause() {
        val paused = current.paused
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_pause)
            .setMessage(
                if (paused) R.string.sf_set_pause_msg_off else R.string.sf_set_pause_msg_on
            )
            .setPositiveButton(
                if (paused) R.string.sf_set_resume_action else R.string.sf_set_pause_action
            ) { _, _ ->
                lifecycleScope.launch {
                    Graph.settings.set(SettingsKeys.PAUSED, !paused)
                    Toast.makeText(
                        this@SettingsActivity,
                        if (paused) R.string.sf_set_resumed_toast else R.string.sf_set_paused_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.sf_set_home_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun languageDialog() {
        val tags = arrayOf("en", "bn")
        val labels = arrayOf("English", "বাংলা")
        val checked = if (UiPrefs.language() == "bn") 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                UiPrefs.setLanguage(tags[which]) // recreates the activity in the new language
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun quoteDialog() {
        val input = EditText(this).apply {
            setText(current.motivationalQuote)
            hint = getString(R.string.sf_set_quote_hint)
            setSingleLine(false)
            minLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_quote)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                lifecycleScope.launch {
                    Graph.settings.set(
                        SettingsKeys.MOTIVATIONAL_QUOTE,
                        input.text?.toString().orEmpty().trim()
                    )
                    Toast.makeText(this@SettingsActivity, R.string.sf_set_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun redirectDialog() {
        showRedirectDialog(current.redirectUrl)
    }

    private fun showRedirectDialog(prefill: String) {
        val input = EditText(this).apply {
            setText(prefill)
            hint = getString(R.string.sf_set_redirect_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_redirect)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val url = input.text?.toString().orEmpty().trim()
                if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) {
                    lifecycleScope.launch {
                        Graph.settings.set(SettingsKeys.REDIRECT_URL, url)
                        Toast.makeText(this@SettingsActivity, R.string.sf_set_saved, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.sf_set_redirect_invalid, Toast.LENGTH_LONG).show()
                    showRedirectDialog(url)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startOfDayDialog() {
        val minutes = current.startOfDayMinutes
        TimePickerDialog(
            this,
            { _, hour, minute ->
                lifecycleScope.launch {
                    Graph.settings.set(SettingsKeys.START_OF_DAY_MINUTES, hour * 60 + minute)
                    Toast.makeText(this@SettingsActivity, R.string.sf_set_saved, Toast.LENGTH_SHORT).show()
                }
            },
            minutes / 60, minutes % 60, false
        ).show()
    }

    private fun timerTypeDialog() {
        val values = arrayOf("onscreen", "notification", "hidden")
        val labels = arrayOf(
            getString(R.string.sf_set_timer_onscreen),
            getString(R.string.sf_set_timer_notification),
            getString(R.string.sf_set_timer_hidden)
        )
        val checked = values.indexOf(current.remainingTimerType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sf_set_timer_type)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                lifecycleScope.launch {
                    Graph.settings.set(SettingsKeys.REMAINING_TIMER_TYPE, values[which])
                    Toast.makeText(this@SettingsActivity, R.string.sf_set_saved, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
