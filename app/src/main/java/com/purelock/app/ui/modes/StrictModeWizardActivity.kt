package com.purelock.app.ui.modes

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.PureLockAdminReceiver
import com.purelock.app.data.prefs.AppMode
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.data.prefs.StrictDeactivation
import com.purelock.app.data.prefs.StrictRestrictionEditing
import com.purelock.app.databinding.ActivityStrictModeWizardBinding
import kotlinx.coroutines.launch

/**
 * Activate Strict Mode wizard (SPEC S06). 4-step accordion:
 * 1 Restriction Editing (radio: Restrict All / Restrict Specific) →
 * 2 Extra Restrictions (toggles: Block Power Off / Block Recent Apps /
 * Block Split Screen / Block Unsupported Browsers, stored in SettingsStore) →
 * 3 Deactivation Method (radios: PIN / Partner PIN / Timer only) →
 * 4 Device Admin. The Activate button stays disabled (grey) until all 4 steps
 * are done; then it sets mode STRICT and finishes.
 */
class StrictModeWizardActivity : AppCompatActivity() {

    private lateinit var b: ActivityStrictModeWizardBinding

    private val stepDone = BooleanArray(4)
    private var expandedStep = 0 // 0-based

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStrictModeWizardBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnClose.setOnClickListener { finish() }

        // Step headers toggle their body (only one expanded at a time).
        b.step1Header.setOnClickListener { expandStep(0) }
        b.step2Header.setOnClickListener { expandStep(1) }
        b.step3Header.setOnClickListener { expandStep(2) }
        b.step4Header.setOnClickListener { expandStep(3) }

        // Step 1 — Restriction Editing: radio choice persists immediately;
        // Next marks the step done.
        lifecycleScope.launch {
            val s = Graph.settings.get()
            selectRestrictionEditing(s.strictRestrictionEditing)
        }
        b.rowRestrictAll.setOnClickListener { selectRestrictionEditing(StrictRestrictionEditing.ALL) }
        b.radioRestrictAll.setOnClickListener { selectRestrictionEditing(StrictRestrictionEditing.ALL) }
        b.rowRestrictSpecific.setOnClickListener {
            selectRestrictionEditing(StrictRestrictionEditing.SPECIFIC)
        }
        b.radioRestrictSpecific.setOnClickListener {
            selectRestrictionEditing(StrictRestrictionEditing.SPECIFIC)
        }
        b.btnStep1Next.setOnClickListener {
            stepDone[0] = true
            expandStep(1)
            updateSteps()
        }

        // Step 2 — Extra Restrictions: toggles persist immediately; Next marks it done.
        lifecycleScope.launch {
            val s = Graph.settings.get()
            b.swExtraPower.isChecked = s.blockPowerOff
            b.swExtraRecent.isChecked = s.blockRecentApps
            b.swExtraSplit.isChecked = s.blockSplitScreen
            b.swExtraBrowsers.isChecked = s.blockUnsupportedBrowsers
        }
        b.swExtraPower.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Graph.settings.set(SettingsKeys.BLOCK_POWER_OFF, checked) }
        }
        b.swExtraRecent.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Graph.settings.set(SettingsKeys.BLOCK_RECENT_APPS, checked) }
        }
        b.swExtraSplit.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Graph.settings.set(SettingsKeys.BLOCK_SPLIT_SCREEN, checked) }
        }
        b.swExtraBrowsers.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                Graph.settings.set(SettingsKeys.BLOCK_UNSUPPORTED_BROWSERS, checked)
            }
        }
        b.btnStep2Next.setOnClickListener {
            stepDone[1] = true
            expandStep(2)
            updateSteps()
        }

        // Step 3 — Deactivation Method: radio choice persists immediately;
        // Next marks the step done.
        lifecycleScope.launch {
            val s = Graph.settings.get()
            selectDeactivation(s.strictDeactivation)
        }
        b.rowDeactPin.setOnClickListener { selectDeactivation(StrictDeactivation.PIN) }
        b.radioDeactPin.setOnClickListener { selectDeactivation(StrictDeactivation.PIN) }
        b.rowDeactPartner.setOnClickListener { selectDeactivation(StrictDeactivation.PARTNER_PIN) }
        b.radioDeactPartner.setOnClickListener { selectDeactivation(StrictDeactivation.PARTNER_PIN) }
        b.rowDeactTimer.setOnClickListener { selectDeactivation(StrictDeactivation.TIMER) }
        b.radioDeactTimer.setOnClickListener { selectDeactivation(StrictDeactivation.TIMER) }
        b.btnStep3Next.setOnClickListener {
            stepDone[2] = true
            expandStep(3)
            updateSteps()
        }

        // Step 4 — Device Admin: launch the system activation flow.
        b.btnDeviceAdmin.setOnClickListener { launchDeviceAdmin() }

        b.btnActivate.setOnClickListener {
            lifecycleScope.launch {
                Graph.settings.setMode(AppMode.STRICT)
                finish()
            }
        }

        updateSteps()
    }

    override fun onResume() {
        super.onResume()
        // The user returns from the system Device Admin screen: re-check.
        if (isAdminActive() && !stepDone[3]) {
            stepDone[3] = true
        }
        updateSteps()
    }

    private fun selectRestrictionEditing(value: String) {
        b.radioRestrictAll.isChecked = (value == StrictRestrictionEditing.ALL)
        b.radioRestrictSpecific.isChecked = (value == StrictRestrictionEditing.SPECIFIC)
        lifecycleScope.launch {
            Graph.settings.set(SettingsKeys.STRICT_RESTRICTION_EDITING, value)
        }
    }

    private fun selectDeactivation(value: String) {
        b.radioDeactPin.isChecked = (value == StrictDeactivation.PIN)
        b.radioDeactPartner.isChecked = (value == StrictDeactivation.PARTNER_PIN)
        b.radioDeactTimer.isChecked = (value == StrictDeactivation.TIMER)
        lifecycleScope.launch {
            Graph.settings.set(SettingsKeys.STRICT_DEACTIVATION, value)
        }
    }

    private fun launchDeviceAdmin() {
        val admin = ComponentName(this, PureLockAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.sf_modes_step3_sub)
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun isAdminActive(): Boolean {
        val admin = ComponentName(this, PureLockAdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
    }

    private fun expandStep(step: Int) {
        expandedStep = step
        updateSteps()
    }

    private fun updateSteps() {
        val bodies = listOf(b.step1Body, b.step2Body, b.step3Body, b.step4Body)
        val numbers = listOf(b.step1Number, b.step2Number, b.step3Number, b.step4Number)
        val chevrons = listOf(b.step1Chevron, b.step2Chevron, b.step3Chevron, b.step4Chevron)
        // Step 4 launches a system screen (chevron > when collapsed); steps 1–3
        // expand inline content (chevron down when collapsed).
        val collapsedIcons = listOf(
            R.drawable.ic_sf_chevron_down,
            R.drawable.ic_sf_chevron_down,
            R.drawable.ic_sf_chevron_down,
            R.drawable.ic_sf_chevron_right
        )
        bodies.forEachIndexed { i, body -> body.isVisible = (i == expandedStep) }
        numbers.forEachIndexed { i, number ->
            val done = stepDone[i]
            number.setBackgroundResource(
                if (done) R.drawable.bg_sf_step_done else R.drawable.bg_sf_step_circle
            )
            number.text = if (done) "✓" else (i + 1).toString()
            number.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (done) android.R.color.white else R.color.sf_text
                )
            )
        }
        chevrons.forEachIndexed { i, chevron ->
            chevron.setImageResource(
                if (i == expandedStep) R.drawable.ic_sf_chevron_up else collapsedIcons[i]
            )
        }
        b.tvStep4Done.isVisible = stepDone[3]

        val allDone = stepDone.all { it }
        b.btnActivate.isEnabled = allDone
        // The PrimaryButton background greys itself when disabled, but the text
        // colour does not auto-grey — set it explicitly (foundation contract).
        b.btnActivate.setTextColor(
            ContextCompat.getColor(
                this,
                if (allDone) android.R.color.white else R.color.sf_muted2
            )
        )
    }
}
