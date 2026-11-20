package com.purelock.app.ui.drawer

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.purelock.app.R
import com.purelock.app.data.prefs.UiPrefs
import com.purelock.app.databinding.ItemSfDrawerRowBinding
import com.purelock.app.databinding.LayoutDrawerBinding
import com.purelock.app.service.BlockReason
import com.purelock.app.ui.MainActivity
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.block.BlockActivity
import com.purelock.app.ui.settings.SettingsActivity

/**
 * Navigation drawer (SPEC S22). [MainActivity] calls [bind] once.
 *
 * Rows in exact spec order: Block Screen, Settings, Rate the App, Share,
 * Organise Dashboard, Backup & Restore, Help, Send feedback, About — then the
 * Dark Mode toggle. "Premium version" in the header is display-only (decided:
 * no purchase flow). Block Screen opens the block overlay as a preview.
 */
object DrawerMenu {
    fun bind(activity: AppCompatActivity, drawerLayout: DrawerLayout) {
        val drawerView = activity.findViewById<View>(R.id.drawerContent) ?: return
        val b = LayoutDrawerBinding.bind(drawerView)

        bindRow(activity, b.rowDrawerBlockScreen, R.drawable.ic_sf_ban, R.string.sf_set_drawer_block_screen) {
            activity.startActivity(
                Intent(activity, BlockActivity::class.java)
                    .putExtra(BlockActivity.EXTRA_LABEL, "PureLock")
                    .putExtra(BlockActivity.EXTRA_REASON, BlockReason.LOCK.name)
            )
        }
        bindRow(activity, b.rowDrawerSettings, R.drawable.ic_sf_gear, R.string.sf_set_drawer_settings) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        bindRow(activity, b.rowDrawerRate, R.drawable.ic_sf_star, R.string.sf_set_drawer_rate) {
            AppActions.rateApp(activity)
        }
        bindRow(activity, b.rowDrawerShare, R.drawable.ic_sf_share, R.string.sf_set_drawer_share) {
            AppActions.shareApp(activity)
        }
        bindRow(activity, b.rowDrawerOrganise, R.drawable.ic_sf_shuffle, R.string.sf_set_drawer_organise) {
            activity.startActivity(Intent(activity, OrganiseDashboardActivity::class.java))
        }
        bindRow(activity, b.rowDrawerBackup, R.drawable.ic_sf_cloud, R.string.sf_set_drawer_backup) {
            activity.startActivity(Intent(activity, BackupRestoreActivity::class.java))
        }
        bindRow(activity, b.rowDrawerHelp, R.drawable.ic_sf_help, R.string.sf_set_drawer_help) {
            activity.startActivity(Intent(activity, HelpActivity::class.java))
        }
        bindRow(activity, b.rowDrawerFeedback, R.drawable.ic_sf_flag, R.string.sf_set_drawer_feedback) {
            AppActions.sendFeedback(activity)
        }
        bindRow(activity, b.rowDrawerAbout, R.drawable.ic_sf_info, R.string.sf_set_drawer_about) {
            activity.startActivity(Intent(activity, AboutActivity::class.java))
        }

        b.switchDrawerDarkMode.isChecked = UiPrefs.theme(activity) == UiPrefs.THEME_DARK
        b.switchDrawerDarkMode.setOnCheckedChangeListener { _, checked ->
            ThemeManager.switchTheme(
                activity,
                if (checked) UiPrefs.THEME_DARK else UiPrefs.THEME_WHITE
            )
        }
        b.rowDrawerDarkMode.setOnClickListener { b.switchDrawerDarkMode.toggle() }
    }

    private fun bindRow(
        activity: AppCompatActivity,
        row: ItemSfDrawerRowBinding,
        icon: Int,
        label: Int,
        onClick: () -> Unit
    ) {
        row.drawerRowIcon.setImageResource(icon)
        row.drawerRowLabel.setText(label)
        row.root.setOnClickListener {
            (activity as? MainActivity)?.closeDrawer()
            onClick()
        }
    }
}
