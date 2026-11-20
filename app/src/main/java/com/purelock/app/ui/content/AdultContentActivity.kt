package com.purelock.app.ui.content

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.databinding.ActivityAdultContentBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.drawer.AppActions
import com.purelock.app.ui.drawer.RowBinder
import kotlinx.coroutines.launch

/**
 * Adult Content settings (FOUNDATION §1 decision 3): master toggle
 * ([SettingsStore] `filterEnabled`) + SafeSearch note + Private DNS guidance
 * card (Kahf Guard Moderate `medium.kahfguard.com` / Cloudflare Family
 * `1.1.1.3`, with a link to https://check.kahfguard.com) + Manage Keywords.
 */
class AdultContentActivity : AppCompatActivity() {

    private lateinit var b: ActivityAdultContentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityAdultContentBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerAdult.tvSfTitle.setText(R.string.sf_set_adult_title)
        b.headerAdult.btnSfBack.setOnClickListener { finish() }

        RowBinder.navRow(
            b.rowAdultKeywords, this, R.drawable.ic_sf_keyword,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_adult_keywords, R.string.sf_set_adult_keywords_desc
        ) { startActivity(Intent(this, KeywordActivity::class.java)) }

        b.tvDnsCheck.setOnClickListener {
            AppActions.openUrl(this, "https://check.kahfguard.com")
        }
        b.btnDnsOpenSettings.setOnClickListener {
            // There is no public intent that opens Private DNS directly; system
            // Settings is where the user sets "Private DNS provider hostname".
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.settings.flow.collect { settings ->
                    // setChecked only fires the listener on a real change, so
                    // re-rendering the stored value never loops back into a save.
                    b.switchAdultMaster.isChecked = settings.filterEnabled
                }
            }
        }
        b.switchAdultMaster.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Graph.settings.set(SettingsKeys.FILTER_ENABLED, checked) }
        }
    }
}
