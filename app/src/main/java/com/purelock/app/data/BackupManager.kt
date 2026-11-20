package com.purelock.app.data

import com.purelock.app.Graph
import com.purelock.app.data.blocklist.DomainMatcher
import com.purelock.app.data.db.AllowedApplication
import com.purelock.app.data.db.AllowedDomain
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.data.db.BlockedDomain
import com.purelock.app.data.db.FocusStatistics
import com.purelock.app.data.db.SafeZone
import com.purelock.app.data.prefs.SettingsKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local backup/restore as a JSON file the user saves wherever they choose (Storage Access Framework).
 * It contains rules and statistics only - never location history, security events, the trusted
 * contact or the active lock - so a backup cannot be used to undo a lock.
 */
object BackupManager {

    private const val VERSION = 1

    private val CONDITION_TYPES = setOf(
        "NONE", "USAGE_LIMIT", "TIME_INTERVAL", "QUICK_BLOCK", "LAUNCH_LIMIT", "WAIT_TIMER", "GOAL", "LOCATION"
    )

    suspend fun export(): String {
        val db = Graph.db
        val s = Graph.settings.get()
        val root = JSONObject()
        root.put("app", "PureLock")
        root.put("version", VERSION)

        root.put("blockedApps", JSONArray().apply {
            db.blockedAppDao().getAll().forEach {
                put(JSONObject().put("package", it.packageName).put("label", it.label)
                    .put("blockDuringLock", it.blockDuringLock).put("dailyLimitMinutes", it.dailyLimitMinutes)
                    .put("conditionType", it.conditionType)
                    .put("hourlyLimitMinutes", it.hourlyLimitMinutes)
                    .put("intervalDays", it.intervalDays)
                    .put("intervalStartMin", it.intervalStartMin)
                    .put("intervalEndMin", it.intervalEndMin)
                    .put("fullDay", it.fullDay)
                    .put("quickBlockUntilEpochMs", it.quickBlockUntilEpochMs)
                    .put("maxLaunchesPerDay", it.maxLaunchesPerDay)
                    .put("maxLaunchesPerHour", it.maxLaunchesPerHour)
                    .put("waitAfterMinutes", it.waitAfterMinutes)
                    .put("waitForMinutes", it.waitForMinutes)
                    .put("waitUntilEpochMs", it.waitUntilEpochMs)
                    .put("goalAppPackage", it.goalAppPackage)
                    .put("goalMinutes", it.goalMinutes)
                    .put("locationZoneId", it.locationZoneId)
                    .put("locationMode", it.locationMode)
                    .put("blockIfLocationUnavailable", it.blockIfLocationUnavailable)
                    .put("blockLaunch", it.blockLaunch)
                    .put("blockNotifications", it.blockNotifications))
            }
        })
        root.put("allowedApps", JSONArray().apply {
            db.allowedAppDao().getAll().forEach {
                put(JSONObject().put("package", it.packageName).put("label", it.label))
            }
        })
        root.put("blockedDomains", JSONArray().apply { db.blockedDomainDao().getAll().forEach { put(it.domain) } })
        root.put("allowedDomains", JSONArray().apply { db.allowedDomainDao().getAll().forEach { put(it.domain) } })
        root.put("safeZones", JSONArray().apply {
            db.safeZoneDao().getAll().forEach {
                put(JSONObject().put("name", it.name).put("lat", it.latitude).put("lng", it.longitude)
                    .put("radiusM", it.radiusM).put("autoLockMinutes", it.autoLockMinutes))
            }
        })
        root.put("statistics", JSONArray().apply {
            db.statsDao().getAll().forEach {
                put(JSONObject().put("date", it.dateKey).put("protectedMs", it.protectedMs)
                    .put("websiteBlocks", it.websiteBlocks).put("appBlocks", it.appBlocks)
                    .put("interruptions", it.interruptions).put("emergencyOverrides", it.emergencyOverrides))
            }
        })
        root.put("settings", JSONObject()
            .put("safeSearch", s.safeSearch)
            .put("uninstallProtection", s.uninstallProtection)
            .put("keepFilterAfterLock", s.keepFilterAfterLock)
            .put("interruptionAlerts", s.interruptionAlerts)
            .put("storeLocationHistory", s.storeLocationHistory))
        return root.toString(2)
    }

    /** Restores a backup. Every value is validated; invalid entries are skipped rather than trusted. */
    suspend fun import(json: String): Result<Unit> = runCatching {
        val root = JSONObject(json)
        require(root.optString("app") == "PureLock") { "Not a PureLock backup" }
        require(root.optInt("version", 0) in 1..VERSION) { "Unsupported backup version" }
        val db = Graph.db
        val now = System.currentTimeMillis()

        root.optJSONArray("blockedApps")?.forEachObject { o ->
            val pkg = o.optString("package")
            if (pkg.isValidPackage()) {
                val conditionType = o.optString("conditionType", "NONE")
                    .takeIf { it in CONDITION_TYPES } ?: "NONE"
                val goalPkg = o.optString("goalAppPackage", "").takeIf { it.isValidPackage() }
                db.blockedAppDao().upsert(
                    BlockedApplication(
                        packageName = pkg,
                        label = o.optString("label", pkg).take(120),
                        blockDuringLock = o.optBoolean("blockDuringLock", true),
                        dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0).coerceIn(0, 1_440),
                        conditionType = conditionType,
                        hourlyLimitMinutes = o.optInt("hourlyLimitMinutes", 0).coerceIn(0, 1_440),
                        intervalDays = o.optInt("intervalDays", 0).coerceIn(0, 127),
                        intervalStartMin = o.optInt("intervalStartMin", 0).coerceIn(0, 1_439),
                        intervalEndMin = o.optInt("intervalEndMin", 0).coerceIn(0, 1_439),
                        fullDay = o.optBoolean("fullDay", false),
                        quickBlockUntilEpochMs = o.optLong("quickBlockUntilEpochMs", 0).coerceAtLeast(0),
                        maxLaunchesPerDay = o.optInt("maxLaunchesPerDay", 0).coerceIn(0, 10_000),
                        maxLaunchesPerHour = o.optInt("maxLaunchesPerHour", 0).coerceIn(0, 10_000),
                        waitAfterMinutes = o.optInt("waitAfterMinutes", 0).coerceIn(0, 1_440),
                        waitForMinutes = o.optInt("waitForMinutes", 0).coerceIn(0, 1_440),
                        waitUntilEpochMs = o.optLong("waitUntilEpochMs", 0).coerceAtLeast(0),
                        goalAppPackage = goalPkg,
                        goalMinutes = o.optInt("goalMinutes", 0).coerceIn(0, 1_440),
                        locationZoneId = o.optLong("locationZoneId", 0).coerceAtLeast(0),
                        locationMode = o.optString("locationMode", "AT").takeIf { it == "AT" || it == "AWAY" } ?: "AT",
                        blockIfLocationUnavailable = o.optBoolean("blockIfLocationUnavailable", false),
                        blockLaunch = o.optBoolean("blockLaunch", true),
                        blockNotifications = o.optBoolean("blockNotifications", false)
                    )
                )
            }
        }
        root.optJSONArray("allowedApps")?.forEachObject { o ->
            val pkg = o.optString("package")
            if (pkg.isValidPackage()) db.allowedAppDao().upsert(AllowedApplication(pkg, o.optString("label", pkg).take(120)))
        }
        root.optJSONArray("blockedDomains")?.forEachString { raw ->
            DomainMatcher.sanitizeInput(raw)?.let { db.blockedDomainDao().upsert(BlockedDomain(it, now)) }
        }
        root.optJSONArray("allowedDomains")?.forEachString { raw ->
            DomainMatcher.sanitizeInput(raw)?.let { db.allowedDomainDao().upsert(AllowedDomain(it, now)) }
        }
        root.optJSONArray("safeZones")?.forEachObject { o ->
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                db.safeZoneDao().upsert(
                    SafeZone(
                        name = o.optString("name", "Zone").take(60),
                        latitude = lat, longitude = lng,
                        radiusM = o.optInt("radiusM", 200).coerceIn(100, 5_000),
                        autoLockMinutes = o.optInt("autoLockMinutes", 0).coerceIn(0, 7 * 24 * 60)
                    )
                )
            }
        }
        root.optJSONArray("statistics")?.forEachObject { o ->
            val date = o.optString("date")
            if (runCatching { DomainDate.parse(date) }.isSuccess) {
                db.statsDao().upsert(
                    FocusStatistics(
                        dateKey = date,
                        protectedMs = o.optLong("protectedMs", 0).coerceAtLeast(0),
                        websiteBlocks = o.optInt("websiteBlocks", 0).coerceAtLeast(0),
                        appBlocks = o.optInt("appBlocks", 0).coerceAtLeast(0),
                        interruptions = o.optInt("interruptions", 0).coerceAtLeast(0),
                        emergencyOverrides = o.optInt("emergencyOverrides", 0).coerceAtLeast(0)
                    )
                )
            }
        }
        root.optJSONObject("settings")?.let { s ->
            val store = Graph.settings
            store.set(SettingsKeys.SAFE_SEARCH, s.optBoolean("safeSearch", true))
            store.set(SettingsKeys.UNINSTALL_PROTECTION, s.optBoolean("uninstallProtection", true))
            store.set(SettingsKeys.KEEP_FILTER_AFTER_LOCK, s.optBoolean("keepFilterAfterLock", false))
            store.set(SettingsKeys.INTERRUPTION_ALERTS, s.optBoolean("interruptionAlerts", true))
            store.set(SettingsKeys.STORE_LOCATION_HISTORY, s.optBoolean("storeLocationHistory", true))
        }
        Graph.blocklist.reload()
        Graph.geofences.sync(null, db.safeZoneDao().getAll())
        Unit
    }

    private fun String.isValidPackage() = matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")) && length <= 200

    private object DomainDate {
        fun parse(s: String) = java.time.LocalDate.parse(s)
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private suspend inline fun JSONArray.forEachString(block: suspend (String) -> Unit) {
        for (i in 0 until length()) optString(i)?.let { block(it) }
    }
}
