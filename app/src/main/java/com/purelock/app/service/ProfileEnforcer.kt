package com.purelock.app.service

import com.purelock.app.data.blocklist.DomainMatcher
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.data.db.FocusProfile

/**
 * Turns the active [FocusProfile]s into the rules [AppBlockPolicy] enforces.
 * Pure (no Android calls): the accessibility service supplies the launcher package
 * list and the two SharedPreferences-backed lookups from [com.purelock.app.data.ProfileRepository].
 *
 * Semantics (SPEC S13):
 * - BLOCKLIST: the condition applies to the profile's listed apps.
 * - WHITELIST: the listed apps are allowed; the condition applies to every other
 *   launcher app (the service enumerates them; [AppBlockPolicy] still never blocks
 *   phone / launcher / system apps).
 * - Only one profile is enforced at a time in practice (the repository's
 *   `activate()` keeps a single profile active); if several are active anyway,
 *   the first profile wins per package and the first Combined limit wins.
 * - Profile rules never set `blockDuringLock`: a profile enforces only its own
 *   condition, never the time/distance lock gate (that stays with explicit per-app
 *   rules). An explicit per-app rule with "App Launch" off still wins over a
 *   profile (see [AppBlockPolicy.reason]).
 * - Combined Usage Limit: the per-package daily/hourly limits are stripped from
 *   the profile's app rules and enforced once, over the selected apps' total
 *   usage, by [AppBlockPolicy]'s combined check.
 * - Sites: a profile's domains are matched in the accessibility URL watch
 *   ([siteRuleFor]); the condition is evaluated against the foreground browser's
 *   usage, so a Usage Limit profile meters sites by the browser app's usage —
 *   an approximation, documented in the service.
 */
object ProfileEnforcer {

    /** One Usage Limit applied to a set of apps' combined usage. */
    data class CombinedLimit(val packages: Set<String>, val limitMs: Long)

    /** One profile's site list + its condition, for the URL watch. */
    data class SiteRule(
        val domains: Set<String>,
        val rule: BlockedApplication,
        val motivation: String
    )

    data class Enforcement(
        val rules: Map<String, BlockedApplication>,
        val motivations: Map<String, String>,
        val combined: CombinedLimit?,
        val siteRules: List<SiteRule>
    )

    fun build(
        profiles: List<FocusProfile>,
        launcherPackages: List<String>,
        isCombined: (Long) -> Boolean,
        quickBlockUntilEpoch: (Long) -> Long
    ): Enforcement {
        val active = profiles.filter { it.active && !it.paused }
        val rules = LinkedHashMap<String, BlockedApplication>()
        val motivations = LinkedHashMap<String, String>()
        var combined: CombinedLimit? = null
        val siteRules = ArrayList<SiteRule>()
        for (profile in active) {
            val until = if (profile.conditionType == "QUICK_BLOCK") {
                quickBlockUntilEpoch(profile.id)
            } else {
                0L
            }
            val base = profile.toBlockedApplication(until).copy(blockDuringLock = false)
            val listed = profile.appPackageList().toSet()
            val isCombinedProfile = profile.conditionType == "USAGE_LIMIT" &&
                isCombined(profile.id) && profile.dailyLimitMinutes > 0
            // Combined: the limit is enforced once over the total, not per app.
            val appRule = if (isCombinedProfile) {
                base.copy(dailyLimitMinutes = 0, hourlyLimitMinutes = 0)
            } else {
                base
            }
            val targets: List<String> = if (profile.type == "WHITELIST") {
                launcherPackages.filter { it !in listed }
            } else {
                listed.toList()
            }
            for (pkg in targets) {
                if (pkg !in rules) {
                    rules[pkg] = appRule.copy(packageName = pkg)
                    motivations[pkg] = profile.effectiveMotivationalText()
                }
            }
            if (isCombinedProfile && combined == null) {
                combined = CombinedLimit(targets.toSet(), profile.dailyLimitMinutes * 60_000L)
            }
            val domains = profile.siteDomainList()
            if (domains.isNotEmpty()) {
                siteRules += SiteRule(domains.toSet(), base, profile.effectiveMotivationalText())
            }
        }
        return Enforcement(rules, motivations, combined, siteRules)
    }

    /** First site rule whose domains match this host (subdomains match, see DomainMatcher). */
    fun siteRuleFor(host: String, rules: List<SiteRule>): SiteRule? =
        rules.firstOrNull { DomainMatcher.matches(host, it.domains) }
}
