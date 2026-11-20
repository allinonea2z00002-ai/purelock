package com.purelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.ui.block.BlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App blocker. It only runs after the user turns it on in Android's Accessibility settings, having been
 * shown what it does (see the disclosure dialog in the Protection screen). It reads only the package
 * name of the app that came to the foreground, plus - while a lock is active and "Uninstall
 * protection" is on - whether a Settings/installer screen shows this app's name, and whether a Vivo /
 * MIUI system screen (security centre, permission manager) is open, which can disable or uninstall
 * PureLock. Nothing it sees is stored or sent anywhere.
 */
class PureLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var policy: AppBlockPolicy

    @Volatile private var lockActive = false
    @Volatile private var uninstallProtection = false
    private var lastBlockedPackage = ""
    private var lastBlockedAt = 0L
    private var lastSettingsScan = 0L
    private var lastGuardAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        policy = AppBlockPolicy(this, Graph.usage)
        scope.launch {
            combine(
                Graph.db.lockDao().observe(),
                Graph.db.blockedAppDao().observeAll(),
                Graph.db.allowedAppDao().observeAll(),
                Graph.settings.flow
            ) { lock, blockedApps, allowedApps, settings ->
                lockActive = lock?.active == true
                uninstallProtection = settings.uninstallProtection
                policy.lockBlocksApps = lock?.active == true && lock.blockApps
                policy.blocked = blockedApps.associateBy { it.packageName }
                policy.allowed = allowedApps.map { it.packageName }.toHashSet()
            }.collect { }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::policy.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg in SETTINGS_PACKAGES) {
            if (lockActive && uninstallProtection) guardSettingsScreen()
            return
        }
        if (isGuardedSystemApp(pkg)) {
            if (lockActive && uninstallProtection) guardSystemApp(pkg)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val reason = policy.reason(pkg) ?: return
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockedAt < 1_500) return
        lastBlockedPackage = pkg
        lastBlockedAt = now
        block(pkg, reason)
    }

    /**
     * While a lock is active, a Settings or installer screen that shows this app's name (its App info,
     * uninstall confirmation, the accessibility/VPN switches) sends the user home. This is the
     * "uninstall protection", and it is stated in the UI. The emergency override always works.
     */
    private fun guardSettingsScreen() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsScan < 700) return
        lastSettingsScan = now
        val root = rootInActiveWindow ?: return
        val found = root.findAccessibilityNodeInfosByText(getString(R.string.app_name))
        if (!found.isNullOrEmpty()) block(root.packageName?.toString() ?: "", BlockReason.SELF_PROTECT)
    }

    /**
     * Vivo / MIUI system screens that can disable or uninstall PureLock (security centre,
     * permission manager). Unlike Settings screens, the package match alone is enough here — the
     * same rule PowerLock uses for these packages — because these screens never show PureLock's
     * name. Fires only while a lock is active and "Uninstall protection" is on.
     */
    private fun isGuardedSystemApp(pkg: String): Boolean =
        pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    private fun guardSystemApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastGuardAt < 1_500) return
        lastGuardAt = now
        block(pkg, BlockReason.SELF_PROTECT)
    }

    private fun block(pkg: String, reason: BlockReason) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_LABEL, label)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name)
        )
        if (reason != BlockReason.SELF_PROTECT) {
            // Blocks during a test lock are trial blocks: they do not count in real statistics.
            scope.launch { if (!Graph.lockManager.isTestLock()) Graph.stats.recordAppBlock() }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
