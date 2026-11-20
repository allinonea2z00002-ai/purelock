package com.purelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.ProfileRepository
import com.purelock.app.data.content.ContentWatch
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.location.Fix
import com.purelock.app.location.LocationHelper
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
 *
 * Content filters (all best-effort, all gated by their own switches):
 * - URL watch: when the adult-content filter is on, the address bar shown on screen is checked
 *   against the blocklist (allowlist wins) and a match sends the user home.
 * - Keyword scan: when keyword blocking is on, visible on-screen text is scanned for the user's
 *   keywords (throttled, at most about once every 2 seconds per app).
 * - Reels/Shorts: when the reels switch and the matching per-feed switch are on, the visible
 *   labels of a distracting feed (for example "Shorts" on YouTube) send the user home.
 * A periodic re-check (about every 60 seconds) also re-applies the app-block policy to the
 * foreground app, so time-based rules take effect without an app switch.
 */
class PureLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var policy: AppBlockPolicy
    private lateinit var profileRepo: ProfileRepository

    @Volatile private var lockActive = false
    @Volatile private var uninstallProtection = false
    @Volatile private var adultFilterOn = false
    private var lastBlockedPackage = ""
    private var lastBlockedAt = 0L
    private var lastSettingsScan = 0L
    private var lastGuardAt = 0L
    private var lastForegroundPackage: String? = null
    private val lastContentScanAt = HashMap<String, Long>()

    private val recheckHandler = Handler(Looper.getMainLooper())
    private val recheckRunnable = object : Runnable {
        override fun run() {
            recheckForeground()
            recheckHandler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        policy = AppBlockPolicy(this, Graph.usage)
        profileRepo = ProfileRepository(this)
        // The blocklist is also used by the URL watch, so load it here too.
        scope.launch { runCatching { Graph.blocklist.reload() } }
        scope.launch {
            combine(
                Graph.db.lockDao().observe(),
                Graph.db.blockedAppDao().observeAll(),
                Graph.db.allowedAppDao().observeAll(),
                Graph.settings.flow
            ) { lock, blockedApps, allowedApps, settings ->
                lockActive = lock?.active == true
                uninstallProtection = settings.uninstallProtection
                adultFilterOn = settings.filterEnabled
                policy.lockBlocksApps = lock?.active == true && lock.blockApps
                policy.blocked = blockedApps.associateBy { it.packageName }
                policy.allowed = allowedApps.map { it.packageName }.toHashSet()
            }.collect { }
        }
        scope.launch {
            combine(
                Graph.db.safeZoneDao().observeAll(),
                Graph.db.locationDao().observeLatest()
            ) { zones, latestLocation ->
                // Location-gate inputs for AppBlockPolicy (zones + latest recorded fix).
                policy.zones = zones.associateBy { it.id }
                policy.lastFix = latestLocation?.let {
                    Fix(it.latitude, it.longitude, it.accuracyM, it.epoch)
                }
            }.collect { }
        }
        // Profile enforcement (Area C): the active profiles' rules are rebuilt into
        // the policy whenever they change — see ProfileEnforcer for the semantics.
        scope.launch {
            Graph.db.profileDao().observeActive().collect { activeProfiles ->
                val launcherPackages = runCatching {
                    packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).map { it.activityInfo.packageName }
                        .filter { it != packageName }
                        .distinct()
                }.getOrDefault(emptyList())
                val enforcement = ProfileEnforcer.build(
                    activeProfiles,
                    launcherPackages,
                    profileRepo::isCombined,
                    profileRepo::quickBlockUntilEpoch
                )
                policy.profileBlocked = enforcement.rules
                policy.profileMotivations = enforcement.motivations
                policy.combinedLimit = enforcement.combined
                policy.profileSiteRules = enforcement.siteRules
            }
        }
        recheckHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
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
        if (policy.isNeverBlocked(pkg)) return

        // Take a Break (SPEC S02/S03, Area A): while a break is active, only
        // whitelisted apps may come to the foreground — everything else is blocked,
        // before the normal app-block policy and content checks run.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            BreakManager.shouldBlockNow(this) && pkg !in BreakManager.whitelist(this)
        ) {
            lastForegroundPackage = pkg
            block(pkg, BlockReason.LOCK)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastForegroundPackage = pkg
                // The app-block policy wins: a fully blocked app never reaches the content checks.
                policy.reason(pkg)?.let { reason ->
                    maybeArmWaitTimer(pkg, reason)
                    block(pkg, reason)
                    return
                }
                checkContent(pkg, throttled = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkContent(pkg, throttled = true)
            }
        }
    }

    /**
     * URL watch, keyword scan and Reels/Shorts detection for the foreground app. Content-changed
     * events are throttled per package; window changes always check. Stops at the first match.
     */
    private fun checkContent(pkg: String, throttled: Boolean) {
        if (throttled) {
            val now = System.currentTimeMillis()
            if (now - (lastContentScanAt[pkg] ?: 0L) < CONTENT_SCAN_THROTTLE_MS) return
            lastContentScanAt[pkg] = now
        }
        val root = rootInActiveWindow ?: return
        // Only judge the app that is actually in front; a background app's content change must
        // never block the foreground app.
        if (root.packageName?.toString() != pkg) return

        // (a) URL watch: when the adult-content filter is on, the address bar shown on screen is checked
        //   against the blocklist (allowlist wins) and a match sends the user home.
        //   Profile sites are checked the same way, with the profile's condition
        //   evaluated for the foreground browser (see AppBlockPolicy.evaluateSiteRule).
        val host = ContentWatch.findAddressBarHost(root)
        if (host != null) {
            if (adultFilterOn && Graph.blocklist.isBlocked(host)) {
                blockContent(pkg, recordWebsiteBlock = true)
                return
            }
            val siteRule = ProfileEnforcer.siteRuleFor(host, policy.profileSiteRules)
            if (siteRule != null && policy.evaluateSiteRule(siteRule.rule, pkg) != null) {
                blockContent(pkg, recordWebsiteBlock = true, motivation = siteRule.motivation)
                return
            }
        }

        val texts = ArrayList<String>()
        ContentWatch.collectTexts(root, texts)
        if (texts.isEmpty()) return

        // (b) Keyword scan: only while keyword blocking is on and keywords exist.
        if (ContentFilterStore.keywordsEnabled(this)) {
            val keywords = ContentFilterStore.keywords(this)
            if (keywords.isNotEmpty() && ContentWatch.findKeyword(texts, keywords) != null) {
                blockContent(pkg, recordWebsiteBlock = false)
                return
            }
        }

        // (c) Reels/Shorts: only while the master switch and that feed's switch are on.
        if (ContentFilterStore.reelsEnabled(this)) {
            val feed = ContentWatch.detectFeed(pkg, texts)
            if (feed != null && ContentFilterStore.feedEnabled(this, feed.storeKey)) {
                blockContent(pkg, recordWebsiteBlock = false)
            }
        }
    }

    /** Re-applies the app-block policy to the foreground app, so time-based rules fire in place. */
    private fun recheckForeground() {
        if (!::policy.isInitialized) return
        refreshLiveFixIfNeeded()
        val pkg = lastForegroundPackage ?: return
        if (pkg == packageName || policy.isNeverBlocked(pkg)) return
        // Take a Break (SPEC S02/S03, Area A): the periodic re-check also enforces
        // the break whitelist, so a break that starts while an app is already open
        // still blocks it.
        if (BreakManager.shouldBlockNow(this) && pkg !in BreakManager.whitelist(this)) {
            block(pkg, BlockReason.LOCK)
            return
        }
        val reason = policy.reason(pkg) ?: return
        maybeArmWaitTimer(pkg, reason)
        block(pkg, reason)
    }

    /** Arms a due Wait Timer exactly once and persists it; a running wait is never re-armed. */
    private fun maybeArmWaitTimer(pkg: String, reason: BlockReason) {
        if (reason != BlockReason.WAIT_TIMER) return
        val rule = policy.blocked[pkg]
        if (rule != null) {
            if (rule.waitUntilEpochMs > System.currentTimeMillis()) return // wait already running
            val armed = policy.armWaitTimer(pkg) ?: return
            scope.launch { runCatching { Graph.db.blockedAppDao().upsert(armed) } }
            return
        }
        // Profile-derived rule: arm in memory only (never persisted as an app rule).
        val profileRule = policy.profileBlocked[pkg] ?: return
        if (profileRule.waitUntilEpochMs > System.currentTimeMillis()) return
        policy.armProfileWaitTimer(pkg)
    }

    /** Keeps the location gate fresh, but only while a location-based rule exists. */
    private fun refreshLiveFixIfNeeded() {
        val hasLocationRule = policy.blocked.values.any { it.locationZoneId > 0 } ||
            policy.profileBlocked.values.any { it.locationZoneId > 0 } ||
            policy.profileSiteRules.any { it.rule.locationZoneId > 0 }
        if (!hasLocationRule) return
        scope.launch {
            runCatching {
                LocationHelper.currentFix(
                    this@PureLockAccessibilityService,
                    highAccuracy = false,
                    timeoutMs = 10_000
                )
            }.getOrNull()?.let { policy.lastFix = it }
        }
    }

    /**
     * While a lock is active, a Settings or installer screen that shows this app's name (its App info,
     * uninstall confirmation, the accessibility switches) sends the user home. This is the
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

    /** Shared 1.5-second de-duplication for every block path, so one screen cannot spam blocks. */
    private fun tryBlock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockedAt < 1_500) return false
        lastBlockedPackage = pkg
        lastBlockedAt = now
        return true
    }

    private fun block(pkg: String, reason: BlockReason) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, reason, policy.motivationFor(pkg))
        if (reason != BlockReason.SELF_PROTECT) {
            scope.launch { Graph.stats.recordAppBlock() }
        }
    }

    /**
     * Content block (blocked site, keyword hit, distracting feed). Shown like any other block,
     * but it is NOT counted as an app block: a site block is recorded as a website block instead,
     * and keyword/feed blocks are not counted in either total. There is no content-specific
     * [BlockReason] (its enum lives in the read-only [AppBlockPolicy] file), so the block screen
     * shows its generic "blocked" title for these.
     */
    private fun blockContent(pkg: String, recordWebsiteBlock: Boolean, motivation: String? = null) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, BlockReason.LOCK, motivation)
        if (recordWebsiteBlock) {
            scope.launch { Graph.stats.recordWebsiteBlock() }
        }
    }

    private fun startBlockActivity(pkg: String, reason: BlockReason, motivation: String? = null) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_LABEL, label)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name)
                .apply { motivation?.let { putExtra(BlockActivity.EXTRA_MOTIVATION, it) } }
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recheckHandler.removeCallbacks(recheckRunnable)
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set

        private const val CONTENT_SCAN_THROTTLE_MS = 2_000L
        private const val RECHECK_INTERVAL_MS = 60_000L

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
