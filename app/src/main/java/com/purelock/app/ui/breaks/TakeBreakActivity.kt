package com.purelock.app.ui.breaks

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.purelock.app.R
import com.purelock.app.databinding.ActivityTakeBreakBinding
import com.purelock.app.service.BreakManager
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

/**
 * Take a Break (SPEC S02/S03): Duration tab (day/hr/min wheels) and Pomodoro tab
 * (Duration/Break/Sessions wheels + explanation), Hide Pause Button and Block
 * Notifications toggles, App Whitelist picker, sticky Start Break button.
 *
 * Starting a break goes through [BreakManager] (time lock + break state). While a
 * break is already running this screen shows its remaining time with Pause / End
 * Break instead of the pickers' Start action being the only path.
 */
class TakeBreakActivity : AppCompatActivity() {

    private lateinit var b: ActivityTakeBreakBinding

    private var pomodoroSelected = false
    private val whitelist = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTakeBreakBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupWheels()
        wireClicks()
        refreshWhitelistRow()
    }

    override fun onResume() {
        super.onResume()
        refreshActiveBreak()
    }

    // ------------------------------------------------------------------ wheels

    private fun setupWheels() {
        // Duration tab (S02): day 0–30, hr 0–23, min 0–59; default 0 day / 0 hr / 30 min,
        // or the duration the Dashboard chip passed in.
        val presetMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
        val presetMin = (presetMs / 60_000L).toInt().coerceAtLeast(0)
        val presetDay = presetMin / 1_440
        val presetHr = (presetMin % 1_440) / 60
        val presetMinRem = presetMin % 60
        b.wheelCol1.setItems((0..30).map { "$it day" }, presetDay.coerceIn(0, 30))
        b.wheelCol2.setItems((0..23).map { "$it hr" }, presetHr)
        b.wheelCol3.setItems((0..59).map { "$it min" }, presetMinRem)
        selectSegment(pomodoro = false)
    }

    private fun selectSegment(pomodoro: Boolean) {
        pomodoroSelected = pomodoro
        if (pomodoro) {
            // Pomodoro tab (S03): Duration 5–120 min, Break 1–30 min, Sessions 1–12.
            // Defaults match the screenshot's selected row: 27 min | 7 min | 6.
            b.wheelCol1.setItems((5..120).map { "$it min" }, 27 - 5)
            b.wheelCol2.setItems((1..30).map { "$it min" }, 7 - 1)
            b.wheelCol3.setItems((1..12).map { "$it" }, 6 - 1)
        } else {
            val presetMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            val presetMin = (presetMs / 60_000L).toInt().coerceAtLeast(0)
            b.wheelCol1.setItems((0..30).map { "$it day" }, (presetMin / 1_440).coerceIn(0, 30))
            b.wheelCol2.setItems((0..23).map { "$it hr" }, (presetMin % 1_440) / 60)
            b.wheelCol3.setItems((0..59).map { "$it min" }, presetMin % 60)
        }
        b.tvBreakHeading.isVisible = !pomodoro
        b.rowPomodoroHeaders.isVisible = pomodoro
        b.tvPomodoroExplain.isVisible = pomodoro
        paintSegment(b.btnSegDuration, selected = !pomodoro)
        paintSegment(b.btnSegPomodoro, selected = pomodoro)
    }

    /** Selected segment = blue pill + white text/icon; unselected = dark text/icon. */
    private fun paintSegment(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(
            if (selected) R.drawable.bg_sf_segment_selected else android.R.color.transparent
        )
        val color = ContextCompat.getColor(
            this, if (selected) android.R.color.white else R.color.sf_text
        )
        tv.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(color))
    }

    // ------------------------------------------------------------------ clicks

    private fun wireClicks() {
        b.btnClose.setOnClickListener { finish() }
        b.btnSegDuration.setOnClickListener { selectSegment(pomodoro = false) }
        b.btnSegPomodoro.setOnClickListener { selectSegment(pomodoro = true) }
        b.btnAddWhitelist.setOnClickListener { showWhitelistPicker() }
        b.btnStartBreak.setOnClickListener { startBreak() }
        b.btnEndBreak.setOnClickListener {
            lifecycleScope.launch {
                BreakManager.endBreak(this@TakeBreakActivity)
                Toast.makeText(this@TakeBreakActivity, R.string.sf_dash_break_ended, Toast.LENGTH_SHORT).show()
                refreshActiveBreak()
            }
        }
        b.btnPauseBreak.setOnClickListener {
            val state = BreakManager.state(this) ?: return@setOnClickListener
            BreakManager.setPaused(this, !state.paused)
            refreshActiveBreak()
        }
    }

    private fun startBreak() {
        val pomodoro: BreakManager.PomodoroConfig?
        val durationMs: Long
        if (pomodoroSelected) {
            val focusMin = b.wheelCol1.selectedIndex + 5
            val breakMin = b.wheelCol2.selectedIndex + 1
            val sessions = b.wheelCol3.selectedIndex + 1
            pomodoro = BreakManager.PomodoroConfig(focusMin, breakMin, sessions)
            durationMs = pomodoro.totalMs
        } else {
            pomodoro = null
            val days = b.wheelCol1.selectedIndex
            val hours = b.wheelCol2.selectedIndex
            val minutes = b.wheelCol3.selectedIndex
            durationMs = ((days * 24L + hours) * 60L + minutes) * 60_000L
        }
        if (durationMs <= 0) {
            Toast.makeText(this, R.string.sf_dash_break_need_duration, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val started = BreakManager.startBreak(
                this@TakeBreakActivity,
                durationMs = durationMs,
                pomodoro = pomodoro,
                hidePause = b.switchHidePause.isChecked,
                blockNotifications = b.switchBlockNotifications.isChecked,
                whitelist = whitelist.toSet()
            )
            if (started) {
                Toast.makeText(this@TakeBreakActivity, R.string.sf_dash_break_started, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@TakeBreakActivity, R.string.sf_dash_break_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ active break

    private fun refreshActiveBreak() {
        val state = BreakManager.state(this)
        b.cardBreakActive.isVisible = state != null
        // Start is only possible when no break is running.
        b.btnStartBreak.isEnabled = state == null
        if (state == null) return
        b.tvBreakRemaining.text = getString(
            R.string.sf_dash_break_remaining,
            DurationFormat.clock(state.remainingMs)
        )
        b.tvBreakPaused.isVisible = state.paused
        b.btnPauseBreak.isVisible = !state.hidePause
        b.btnPauseBreak.setText(
            if (state.paused) R.string.sf_dash_resume_break else R.string.sf_dash_pause_break
        )
    }

    // --------------------------------------------------------------- whitelist

    private data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

    private fun launchableApps(): List<LaunchableApp> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
        return infos.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) null
            else LaunchableApp(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm))
        }.sortedBy { it.label.lowercase() }
    }

    private fun showWhitelistPicker() {
        val apps = launchableApps()
        val labels = apps.map { it.label }.toTypedArray()
        val checked = apps.map { it.packageName in whitelist }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sf_dash_whitelist_pick)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].packageName
                if (isChecked) whitelist.add(pkg) else whitelist.remove(pkg)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> refreshWhitelistRow() }
            .setNegativeButton(R.string.sf_dash_cancel, null)
            .show()
    }

    private fun refreshWhitelistRow() {
        b.llWhitelist.removeAllViews()
        val pm = packageManager
        for (pkg in whitelist) {
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(48, 48).apply { marginEnd = 8 }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(
                    runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                )
                contentDescription = pkg
                setOnClickListener {
                    whitelist.remove(pkg)
                    refreshWhitelistRow()
                }
            }
            b.llWhitelist.addView(icon)
        }
        b.tvWhitelistEmpty.isVisible = whitelist.isEmpty()
    }

    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        const val DEFAULT_DURATION_MS = 30L * 60_000L
    }
}
