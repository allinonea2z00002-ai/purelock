package com.purelock.app.ui.content

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.purelock.app.R
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.databinding.ActivityKeywordsBinding
import com.purelock.app.ui.ThemeManager

/**
 * Manage Keywords (SPEC S10): grey input bar + ADD, "NO KEYWORDS ADDED" empty state,
 * removable chips when keywords exist, an info button explaining keyword blocking,
 * and a sticky Save button. Keywords are stored in [ContentFilterStore] (added as
 * typed, comma-separated); Save just returns to the Dashboard. The on/off master
 * switch lives on the Dashboard card, not on this screen.
 *
 * Keyword blocking: when the accessibility service sees one of these words in visible
 * on-screen text, it sends the user home. Best-effort only: text inside images, video,
 * or apps that hide their content cannot be seen.
 */
class KeywordActivity : AppCompatActivity() {

    private lateinit var b: ActivityKeywordsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityKeywordsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnInfo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.sf_apps_kw_info_title)
                .setMessage(R.string.sf_apps_kw_info_text)
                .setPositiveButton(R.string.action_close, null)
                .show()
        }

        b.btnAdd.setOnClickListener { addFromInput() }
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput()
                true
            } else false
        }
        b.btnSave.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Splits the input on commas so several keywords can be added at once (S10 hint). */
    private fun addFromInput() {
        val text = b.etKeyword.text?.toString().orEmpty()
        var added = false
        for (part in text.split(",")) {
            if (ContentFilterStore.addKeyword(this, part)) added = true
        }
        if (added) b.etKeyword.text?.clear()
        refresh()
    }

    private fun refresh() {
        val keywords = ContentFilterStore.keywords(this)
        b.chipGroupKeywords.removeAllViews()
        for (keyword in keywords) {
            val chip = Chip(this).apply {
                text = keyword
                isCloseIconVisible = true
                closeIcon = ContextCompat.getDrawable(context, R.drawable.ic_sf_close)
                closeIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.sf_text))
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.sf_blue_tint)
                )
                setTextColor(ContextCompat.getColor(context, R.color.sf_text))
                setOnCloseIconClickListener {
                    ContentFilterStore.removeKeyword(this@KeywordActivity, keyword)
                    refresh()
                }
            }
            b.chipGroupKeywords.addView(chip)
        }
        b.layoutEmpty.isVisible = keywords.isEmpty()
        b.chipGroupKeywords.isVisible = keywords.isNotEmpty()
    }
}
