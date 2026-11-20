package com.purelock.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.purelock.app.R
import com.purelock.app.databinding.ActivityMainBinding
import com.purelock.app.ui.account.AccountFragment
import com.purelock.app.ui.dashboard.DashboardFragment
import com.purelock.app.ui.drawer.DrawerMenu
import com.purelock.app.ui.modes.ModesFragment
import com.purelock.app.ui.stats.StatsFragment

/**
 * Main shell: 4-tab bottom nav — Dashboard / Modes / Analytics / Account (SPEC §1.3) —
 * plus the left navigation drawer (S22). The old 5-tab shell (Home / Analysis / Map /
 * Stats / Settings) and WelcomeActivity are gone; the Dashboard IS the first screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the app works either way; the UI reports it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }

        DrawerMenu.bind(this, binding.drawerLayout)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = tabFromIntent(intent)
            requestNotificationPermissionIfNeeded()
        }

        binding.root.post { ThemeManager.playTransitionIn(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.bottomNav.selectedItemId = tabFromIntent(intent)
    }

    fun openTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** Opens the left navigation drawer (S22); called by the hamburger icon on Dashboard/Modes. */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) closeDrawer()
        else super.onBackPressed()
    }

    private fun tabFromIntent(intent: Intent?): Int = when (intent?.getStringExtra(EXTRA_TAB)) {
        "modes" -> R.id.nav_modes
        "analytics", "stats", "statistics" -> R.id.nav_analytics
        "account" -> R.id.nav_account
        "dashboard" -> R.id.nav_dashboard
        // Old extras map to the nearest new tab.
        "protection", "analysis", "website", "location", "map" -> R.id.nav_dashboard
        "settings" -> R.id.nav_account
        else -> R.id.nav_dashboard
    }

    private fun show(itemId: Int) {
        val tag = "tab_$itemId"
        val fragment: Fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_modes -> ModesFragment()
            R.id.nav_analytics -> StatsFragment()
            R.id.nav_account -> AccountFragment()
            else -> DashboardFragment()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            .replace(R.id.container, fragment, tag)
            .commit()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
