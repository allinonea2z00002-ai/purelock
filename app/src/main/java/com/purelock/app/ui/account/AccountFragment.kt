package com.purelock.app.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.purelock.app.BuildConfig
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.prefs.UiPrefs
import com.purelock.app.databinding.FragmentAccountBinding
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.drawer.AppActions
import com.purelock.app.ui.drawer.BackupRestoreActivity
import com.purelock.app.ui.drawer.HelpActivity
import com.purelock.app.ui.drawer.AboutActivity
import com.purelock.app.ui.drawer.RowBinder
import com.purelock.app.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Account tab (FOUNDATION §1 decision 1): profile header (PureLock icon,
 * "PureLock User", version) + stats summary (real streak and total blocks
 * from [StatsCalculator]) + rows: Settings, Backup & Restore, Help,
 * Send feedback, About, Rate the App, Share + Dark Mode toggle.
 */
class AccountFragment : Fragment(R.layout.fragment_account) {

    private var _binding: FragmentAccountBinding? = null
    private val b get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAccountBinding.bind(view)

        b.tvAcctVersion.text = getString(R.string.sf_set_about_version, BuildConfig.VERSION_NAME)

        RowBinder.navRow(
            b.rowAcctSettings, requireContext(), R.drawable.ic_sf_gear,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_drawer_settings
        ) { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        RowBinder.navRow(
            b.rowAcctBackup, requireContext(), R.drawable.ic_sf_cloud,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_backup_title
        ) { startActivity(Intent(requireContext(), BackupRestoreActivity::class.java)) }
        RowBinder.navRow(
            b.rowAcctHelp, requireContext(), R.drawable.ic_sf_help,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_help_title
        ) { startActivity(Intent(requireContext(), HelpActivity::class.java)) }
        RowBinder.navRow(
            b.rowAcctFeedback, requireContext(), R.drawable.ic_sf_flag,
            R.color.sf_pink, R.color.sf_red,
            R.string.sf_acct_feedback
        ) { AppActions.sendFeedback(requireContext()) }
        RowBinder.navRow(
            b.rowAcctAbout, requireContext(), R.drawable.ic_sf_info,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_set_about_title
        ) { startActivity(Intent(requireContext(), AboutActivity::class.java)) }
        RowBinder.navRow(
            b.rowAcctRate, requireContext(), R.drawable.ic_sf_star,
            R.color.sf_yellow, R.color.sf_warning,
            R.string.sf_acct_rate
        ) { AppActions.rateApp(requireContext()) }
        RowBinder.navRow(
            b.rowAcctShare, requireContext(), R.drawable.ic_sf_share,
            R.color.sf_blue_tint, R.color.sf_primary,
            R.string.sf_acct_share
        ) { AppActions.shareApp(requireContext()) }
        RowBinder.toggleRow(
            b.rowAcctDarkMode, requireContext(), R.drawable.ic_sf_moon,
            R.color.sf_pill_active, R.color.sf_gradient_start,
            R.string.sf_set_drawer_dark_mode,
            initial = UiPrefs.theme(requireContext()) == UiPrefs.THEME_DARK
        ) { checked ->
            ThemeManager.switchTheme(
                requireActivity(),
                if (checked) UiPrefs.THEME_DARK else UiPrefs.THEME_WHITE
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { Graph.db.statsDao().getAll() }
            val summary = StatsCalculator.summarize(rows, LocalDate.now())
            if (_binding == null) return@launch
            b.tvAcctStreakValue.text = summary.streakDays.toString()
            b.tvAcctBlocksValue.text = (summary.websiteBlocks + summary.appBlocks).toString()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
