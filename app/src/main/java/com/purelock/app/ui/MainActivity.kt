package com.purelock.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.purelock.app.R
import com.purelock.app.databinding.ActivityMainBinding
import com.purelock.app.ui.home.HomeFragment
import com.purelock.app.ui.location.LocationFragment
import com.purelock.app.ui.protection.ProtectionFragment
import com.purelock.app.ui.settings.SettingsFragment
import com.purelock.app.ui.stats.StatsFragment
import com.purelock.app.domain.LockMode

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Protection tab to open once ("smart" / "website" / "apps"); cleared when read. */
    var pendingProtectionTab: String? = null

    /** Lock mode to preselect in Protection; cleared when read. */
    var pendingLockMode: LockMode? = null

    /** Opens Protection on the given tab ("smart", "website" or "apps"). */
    fun openProtectionTab(tab: String) {
        pendingProtectionTab = tab
        openTab(R.id.nav_analysis)
    }

    /** Opens Protection with the given lock mode preselected. */
    fun openLockMode(mode: LockMode) {
        pendingLockMode = mode
        openTab(R.id.nav_analysis)
    }

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

    private fun tabFromIntent(intent: Intent?): Int = when (intent?.getStringExtra(EXTRA_TAB)) {
        // Old extras still work: they now map to the renamed tabs.
        "protection", "analysis" -> R.id.nav_analysis
        "location", "map" -> R.id.nav_map
        "stats", "statistics" -> R.id.nav_stats
        "settings" -> R.id.nav_settings
        else -> R.id.nav_home
    }

    private fun show(itemId: Int) {
        val tag = "tab_$itemId"
        val fragment: Fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_analysis -> ProtectionFragment()
            R.id.nav_stats -> StatsFragment()
            R.id.nav_map -> LocationFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
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
