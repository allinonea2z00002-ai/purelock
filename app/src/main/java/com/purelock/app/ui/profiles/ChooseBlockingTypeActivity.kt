package com.purelock.app.ui.profiles

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.databinding.ActivityChooseBlockingTypeBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.apps.ConditionActivity

/**
 * Choose Blocking Type (SPEC S13): Blocklist / Whitelist cards. Tap a card to select
 * it (blue ring), then Continue opens [ConditionActivity] in the profile flow
 * (forward mode: tapping a condition goes straight to the schedule).
 */
class ChooseBlockingTypeActivity : AppCompatActivity() {

    private lateinit var b: ActivityChooseBlockingTypeBinding

    private var draft: FocusProfile = ProfileFlow.freshDraft()
    private var combined = false
    private var selectedType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityChooseBlockingTypeBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }

        val (incomingDraft, incomingCombined) = ProfileFlow.draftFrom(intent)
        draft = incomingDraft
        combined = incomingCombined
        // Only a template or an edit prefills the type; a fresh flow starts unselected.
        val prefilled = intent.getLongExtra(ProfileFlow.EXTRA_PROFILE_ID, 0L) > 0 ||
            intent.hasExtra(ProfileFlow.EXTRA_PROFILE_TEMPLATE)
        if (prefilled && (draft.type == "BLOCKLIST" || draft.type == "WHITELIST")) {
            select(draft.type)
        } else {
            b.btnContinue.setTextColor(ContextCompat.getColor(this, R.color.sf_muted2))
        }

        b.cardBlocklist.setOnClickListener { select("BLOCKLIST") }
        b.cardWhitelist.setOnClickListener { select("WHITELIST") }
        b.btnContinue.setOnClickListener { next() }
    }

    private fun select(type: String) {
        selectedType = type
        val selectedStroke = ContextCompat.getColor(this, R.color.sf_primary)
        b.cardBlocklist.strokeWidth = if (type == "BLOCKLIST") SELECTED_STROKE_DP.dp() else 0
        b.cardBlocklist.strokeColor = selectedStroke
        b.cardWhitelist.strokeWidth = if (type == "WHITELIST") SELECTED_STROKE_DP.dp() else 0
        b.cardWhitelist.strokeColor = selectedStroke
        b.btnContinue.isEnabled = true
        b.btnContinue.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun next() {
        val type = selectedType ?: return
        val nextDraft = draft.copy(type = type)
        val intent = Intent(this, ConditionActivity::class.java).apply {
            ProfileFlow.putDraft(this, nextDraft, combined)
            putExtra(ProfileFlow.EXTRA_FORWARD, true)
        }
        startActivity(intent)
    }

    companion object {
        private const val SELECTED_STROKE_DP = 2
    }
}
