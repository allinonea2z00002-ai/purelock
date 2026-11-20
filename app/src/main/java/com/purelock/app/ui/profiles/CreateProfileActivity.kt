package com.purelock.app.ui.profiles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.FocusProfile
import com.purelock.app.databinding.ActivityCreateProfileBinding
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create New Profile, step 1 (SPEC S12): name field, Next grey until a name is typed.
 * Next opens [ChooseBlockingTypeActivity] with the draft; the rest of the wizard
 * (type → condition → schedule → pick apps/sites) forwards the draft through
 * [ProfileFlow] and saves at the end. Edit mode loads the existing profile and
 * walks the same flow prefilled; template mode starts from a [ProfileTemplates] prefill.
 */
class CreateProfileActivity : AppCompatActivity() {

    private lateinit var b: ActivityCreateProfileBinding

    private var draft: FocusProfile = ProfileFlow.freshDraft()
    private var combined = false
    private var draftReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityCreateProfileBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        b.etProfileName.doAfterTextChanged { syncNext() }
        b.btnNext.setOnClickListener { next() }

        val editId = intent.getLongExtra(ProfileFlow.EXTRA_PROFILE_ID, 0L)
        val template = intent.getStringExtra(ProfileFlow.EXTRA_PROFILE_TEMPLATE)
        when {
            editId > 0 -> loadForEdit(editId)
            template != null -> {
                val prefill = ProfileTemplates.forTemplate(this, template)
                if (prefill != null) {
                    draft = prefill
                    draftReady = true
                    b.etProfileName.setText(prefill.name)
                    syncNext()
                } else {
                    draftReady = true
                }
            }
            else -> draftReady = true
        }
        syncNext()
    }

    private fun loadForEdit(profileId: Long) {
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { Graph.db.profileDao().get(profileId) }
            if (profile == null) {
                Toast.makeText(this@CreateProfileActivity, R.string.sf_profile_not_found, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            draft = profile
            draftReady = true
            b.etProfileName.setText(profile.name)
            syncNext()
        }
    }

    private fun syncNext() {
        val hasName = b.etProfileName.text?.toString()?.isNotBlank() == true
        b.btnNext.isEnabled = draftReady && hasName
        b.btnNext.setTextColor(
            ContextCompat.getColor(
                this,
                if (b.btnNext.isEnabled) android.R.color.white else R.color.sf_muted2
            )
        )
    }

    private fun next() {
        val name = b.etProfileName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.sf_profile_need_name, Toast.LENGTH_LONG).show()
            return
        }
        val nextDraft = draft.copy(name = name)
        val intent = Intent(this, ChooseBlockingTypeActivity::class.java)
        ProfileFlow.putDraft(intent, nextDraft, combined)
        startActivity(intent)
    }

    companion object {
        /** Fresh create flow. */
        fun intentFor(context: Context): Intent =
            Intent(context, CreateProfileActivity::class.java)

        /** Create flow prefilled from a template (`FOCUS_HOURS` / `SOCIAL_LIMIT`). */
        fun intentForTemplate(context: Context, template: String): Intent =
            Intent(context, CreateProfileActivity::class.java).apply {
                putExtra(ProfileFlow.EXTRA_PROFILE_TEMPLATE, template)
            }

        /** Edit flow for an existing profile (walks the same steps, prefilled). */
        fun intentForEdit(context: Context, profileId: Long): Intent =
            Intent(context, CreateProfileActivity::class.java).apply {
                putExtra(ProfileFlow.EXTRA_PROFILE_ID, profileId)
            }
    }
}
