package com.purelock.app.ui.modes

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.PureLockAdminReceiver
import com.purelock.app.data.prefs.AppMode
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.databinding.ActivityLockModeWizardBinding
import kotlinx.coroutines.launch

/**
 * Activate Lock Mode wizard (SPEC S05). 4-step accordion:
 * 1 Blocking Level (toggles) → 2 Set Password (4-digit PIN, SHA-256 hash in
 * SecureStore.lockPinHash) → 3 Device Admin → 4 Draw-over-apps. The Activate
 * button stays disabled (grey) until all 4 steps are done; then it sets mode
 * LOCK and finishes.
 */
class LockModeWizardActivity : AppCompatActivity() {

    private lateinit var b: ActivityLockModeWizardBinding

    private val stepDone = BooleanArray(4)
    private var expandedStep = 0 // 0-based

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLockModeWizardBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnClose.setOnClickListener { finish() }

        // Step headers toggle their body (only one expanded at a time).
        b.step1Header.setOnClickListener { expandStep(0) }
        b.step2Header.setOnClickListener { expandStep(1) }
        b.step3Header.setOnClickListener { expandStep(2) }
        b.step4Header.setOnClickListener { expandStep(3) }

        // Step 1 — Blocking Level: toggles persist immediately; Next marks it done.
        lifecycleScope.launch {
            val s = Graph.settings.get()
            b.swLockApp.isChecked = s.lockBlockApp
            b.swLockRestrict.isChecked = s.lockBlockRestrictionEditing
            b.swLockPhone.isChecked = s.lockBlockPhoneSettings
        }
        b.swLockApp.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Graph.settings.set(SettingsKeys.LOCK_BLOCK_APP, checked) }
        }
        b.swLockRestrict.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                Graph.settings.set(SettingsKeys.LOCK_BLOCK_RESTRICTION_EDITING, checked)
            }
        }
        b.swLockPhone.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                Graph.settings.set(SettingsKeys.LOCK_BLOCK_PHONE_SETTINGS, checked)
            }
        }
        b.btnStep1Next.setOnClickListener {
            stepDone[0] = true
            expandStep(1)
            updateSteps()
        }

        // Step 2 — Set Password: 4-digit PIN + confirm, stored as SHA-256 hash.
        b.btnSavePin.setOnClickListener { savePin() }

        // Step 3 — Device Admin: launch the system activation flow.
        b.btnDeviceAdmin.setOnClickListener { launchDeviceAdmin() }

        // Step 4 — Draw over other apps: open the system overlay-permission screen.
        b.btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }
        }

        b.btnActivate.setOnClickListener {
            lifecycleScope.launch {
                Graph.settings.setMode(AppMode.LOCK)
                finish()
            }
        }

        updateSteps()
    }

    override fun onResume() {
        super.onResume()
        // The user returns from a system screen (Device Admin / overlay): re-check.
        if (isAdminActive()) {
            if (!stepDone[2]) {
                stepDone[2] = true
                expandStep(3)
            }
        }
        if (Settings.canDrawOverlays(this)) {
            stepDone[3] = true
        }
        updateSteps()
    }

    private fun savePin() {
        val pin = b.etPin.text?.toString().orEmpty()
        val confirm = b.etPinConfirm.text?.toString().orEmpty()
        when {
            pin.length != 4 -> showPinError(R.string.sf_modes_pin_short)
            pin != confirm -> showPinError(R.string.sf_modes_pin_mismatch)
            else -> {
                Graph.secure.setLockPin(pin)
                b.tvPinError.isVisible = false
                b.etPin.setText("")
                b.etPinConfirm.setText("")
                stepDone[1] = true
                Toast.makeText(this, R.string.sf_modes_pin_saved, Toast.LENGTH_SHORT).show()
                expandStep(2)
                updateSteps()
            }
        }
    }

    private fun showPinError(messageRes: Int) {
        b.tvPinError.setText(messageRes)
        b.tvPinError.isVisible = true
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
        // Steps 3/4 launch a system screen (chevron > when collapsed); steps 1/2
        // expand inline content (chevron down when collapsed).
        val collapsedIcons = listOf(
            R.drawable.ic_sf_chevron_down,
            R.drawable.ic_sf_chevron_down,
            R.drawable.ic_sf_chevron_right,
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
        b.tvStep3Done.isVisible = stepDone[2]
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
