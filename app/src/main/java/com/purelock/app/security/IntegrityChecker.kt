package com.purelock.app.security

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.purelock.app.R
import com.purelock.app.data.EventLog
import com.purelock.app.data.StatsRepository
import com.purelock.app.data.db.AppDatabase
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.db.UserPreferences
import com.purelock.app.data.prefs.SettingsStore
import com.purelock.app.domain.LockMode
import com.purelock.app.location.LocationHelper
import com.purelock.app.service.PureLockAccessibilityService
import com.purelock.app.service.UsageRepository
import com.purelock.app.util.Notifier

enum class Issue(val critical: Boolean) {
    ACCESSIBILITY_OFF(true),
    LOCATION_PERMISSION(true),
    BACKGROUND_LOCATION(true),
    LOCATION_DISABLED(true),
    LOCATION_STALE(true),
    NOTIFICATIONS_OFF(false),
    USAGE_ACCESS_OFF(false),
    PRIVATE_DNS(false),
    BATTERY_OPTIMIZED(false)
}

data class IntegrityReport(
    val lockActive: Boolean,
    val issues: Set<Issue>
) {
    val criticalIssues: Set<Issue> get() = issues.filter { it.critical }.toSet()
    /** True only when a lock is active and nothing it depends on is broken. */
    val protectionIntact: Boolean get() = lockActive && criticalIssues.isEmpty()
}

/**
 * Checks - honestly - whether the things an active lock depends on are really working right now.
 * An ordinary app cannot stop the user from turning these off; it can notice and say so.
 */
class IntegrityChecker(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val eventLog: EventLog,
    private val stats: StatsRepository,
    private val notifier: Notifier,
    private val usage: UsageRepository
) {

    suspend fun check(): IntegrityReport {
        val cfg = db.lockDao().get()
        val s = settings.get()
        val lockActive = cfg?.active == true
        val mode = LockMode.fromName(cfg?.mode)
        val blockedApps = db.blockedAppDao().getAll()
        val hasLimits = blockedApps.any { it.dailyLimitMinutes > 0 }

        val needsFilter = (lockActive && cfg?.blockAdult == true) || s.filterEnabled
        val needsApps = blockedApps.isNotEmpty() && ((lockActive && cfg?.blockApps == true) || hasLimits)
        val needsLocation = lockActive && mode.usesDistance

        val issues = LinkedHashSet<Issue>()
        if (needsFilter && isStrictPrivateDns()) issues += Issue.PRIVATE_DNS
        if (needsApps && !isAccessibilityEnabled()) issues += Issue.ACCESSIBILITY_OFF
        if (needsApps && hasLimits && !usage.hasAccess()) issues += Issue.USAGE_ACCESS_OFF

        if (needsLocation) {
            if (!LocationHelper.hasForegroundPermission(context)) {
                issues += Issue.LOCATION_PERMISSION
            } else {
                if (!LocationHelper.hasBackgroundPermission(context)) issues += Issue.BACKGROUND_LOCATION
                if (!LocationHelper.isLocationEnabled(context)) issues += Issue.LOCATION_DISABLED
                val last = maxOf(cfg?.lastLocationEpoch ?: 0L, cfg?.startEpoch ?: 0L)
                if (System.currentTimeMillis() - last > STALE_LOCATION_MS) issues += Issue.LOCATION_STALE
            }
        }

        if (lockActive) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) issues += Issue.NOTIFICATIONS_OFF
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) issues += Issue.BATTERY_OPTIMIZED
        }
        return IntegrityReport(lockActive, issues)
    }

    /**
     * Periodic check: records new interruptions and notifies the user when protection breaks or recovers.
     * There is no VPN to restart any more: website blocking is the accessibility URL-watch plus
     * the phone's Private DNS, neither of which this app can (or should) restart.
     */
    suspend fun evaluateAndNotify() {
        val report = check()

        val previous = db.prefDao().get(KEY_LAST_ISSUES).orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()
        val current = report.criticalIssues.map { it.name }.toSet()
        val newlyBroken = current - previous

        if (newlyBroken.isNotEmpty() && report.lockActive) {
            eventLog.log(SecurityEventType.INTEGRITY_ISSUE, newlyBroken.joinToString(","))
            stats.recordInterruption()
            if (settings.get().interruptionAlerts) {
                notifier.alert(
                    Notifier.ID_INTEGRITY,
                    context.getString(R.string.notif_integrity_title),
                    context.getString(R.string.notif_integrity_text)
                )
            }
        } else if (current.isEmpty() && previous.isNotEmpty()) {
            eventLog.log(SecurityEventType.INTEGRITY_RESTORED, "")
        }
        db.prefDao().set(UserPreferences(KEY_LAST_ISSUES, current.joinToString(",")))
    }

    fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return false
        val list = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(list) }
        val ours = ComponentName(context, PureLockAccessibilityService::class.java)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == ours) return true
        }
        return false
    }

    /** "Private DNS" in strict hostname mode sends DNS to a fixed server. If that server is not a
     * family filter (Kahf Guard / Cloudflare Family), the Private DNS layer blocks nothing. */
    private fun isStrictPrivateDns(): Boolean =
        Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"

    private companion object {
        const val KEY_LAST_ISSUES = "last_integrity_issues"
        const val STALE_LOCATION_MS = 45L * 60_000L
    }
}
