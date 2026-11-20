package com.purelock.app.ui.apps

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.db.AllowedApplication
import com.purelock.app.data.db.BlockedApplication
import com.purelock.app.service.AppBlockPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sort orders from the All Apps sort popup (SPEC S07). */
enum class AppSort { NAME, BLOCKED, LAUNCHES, USAGE, PINNED }

data class AppItem(
    val packageName: String,
    val label: String,
    val rule: BlockedApplication?,
    val allowed: Boolean,
    val usageTodayMs: Long,
    val launchesToday: Int = 0,
    val pinned: Boolean = false,
    /** Phone/launcher/system apps can never be blocked. */
    val alwaysAvailable: Boolean
)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private data class Installed(val packageName: String, val label: String)

    private val installed = MutableStateFlow<List<Installed>>(emptyList())
    private val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val launches = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(AppSort.NAME)
    private val pins = MutableStateFlow(PinStore.pinned(app, PinStore.KIND_APPS))
    private val policy = AppBlockPolicy(app, Graph.usage)

    val loading = MutableStateFlow(true)

    /** Current sort order, for the sort button label. */
    val sortOrder: StateFlow<AppSort> = sort

    private val lists = combine(
        Graph.db.blockedAppDao().observeAll(),
        Graph.db.allowedAppDao().observeAll()
    ) { blocked, allowed -> blocked.associateBy { it.packageName } to allowed.map { it.packageName }.toSet() }

    /**
     * Base app rows (label, rule, usage, launches) — no filtering or sorting.
     * Kept as a 4-flow combine: the 7-flow combine failed overload resolution
     * (same SuspendFunction7-vs-SuspendFunction1 error as the 6-flow combine
     * in PureLockAccessibilityService, fixed in 0d2f250 by splitting).
     */
    private val baseItems: Flow<List<AppItem>> = combine(
        installed, lists, usage, launches
    ) { apps, pair, usageMap, launchesMap ->
        val (blocked, allowed) = pair
        apps.map {
            AppItem(
                packageName = it.packageName,
                label = it.label,
                rule = blocked[it.packageName],
                allowed = it.packageName in allowed,
                usageTodayMs = usageMap[it.packageName] ?: 0L,
                launchesToday = launchesMap[it.packageName] ?: 0,
                pinned = false,
                alwaysAvailable = policy.isNeverBlocked(it.packageName)
            )
        }
    }

    val items: StateFlow<List<AppItem>> = combine(
        baseItems, query, sort, pins
    ) { apps, q, sortOrder, pinned ->
        val filtered = apps.asSequence()
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .map { if (it.packageName in pinned) it.copy(pinned = true) else it }
        when (sortOrder) {
            AppSort.NAME -> filtered.sortedBy { it.label.lowercase() }
            AppSort.BLOCKED -> filtered.sortedWith(
                compareByDescending<AppItem> { it.rule != null }.thenBy { it.label.lowercase() }
            )
            AppSort.LAUNCHES -> filtered.sortedWith(
                compareByDescending<AppItem> { it.launchesToday }.thenBy { it.label.lowercase() }
            )
            AppSort.USAGE -> filtered.sortedWith(
                compareByDescending<AppItem> { it.usageTodayMs }.thenBy { it.label.lowercase() }
            )
            AppSort.PINNED -> filtered.sortedWith(
                compareByDescending<AppItem> { it.pinned }.thenBy { it.label.lowercase() }
            )
        }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val pm = app.packageManager
            val launchable = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                )
                    .map { Installed(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .filter { it.packageName != app.packageName }
                    .distinctBy { it.packageName }
            }
            installed.value = launchable
            usage.value = withContext(Dispatchers.IO) { Graph.usage.allToday() }
            launches.value = withContext(Dispatchers.IO) {
                Graph.usage.allLaunchesToday().associate { it.packageName to it.launches }
            }
            loading.value = false
        }
    }

    fun setQuery(text: String) { query.value = text }

    fun setSort(order: AppSort) { sort.value = order }

    /** Long-press a row to pin/unpin it. Returns the new pinned state. */
    fun togglePin(packageName: String): Boolean {
        val nowPinned = packageName !in pins.value
        PinStore.setPinned(getApplication(), PinStore.KIND_APPS, packageName, nowPinned)
        pins.value = PinStore.pinned(getApplication(), PinStore.KIND_APPS)
        return nowPinned
    }

    /**
     * Saves the rule for one app. While a lock is active rules may only get stricter: blocks cannot be
     * removed, limits cannot be raised and nothing can be added to the allowlist.
     * Returns false if the change was refused for that reason.
     */
    suspend fun saveRule(
        item: AppItem,
        block: Boolean,
        blockDuringLock: Boolean,
        limitMinutes: Int,
        allowed: Boolean
    ): Boolean {
        val base = item.rule?.copy(blockDuringLock = blockDuringLock, dailyLimitMinutes = limitMinutes.coerceIn(0, 1_440))
            ?: BlockedApplication(item.packageName, item.label, blockDuringLock, limitMinutes.coerceIn(0, 1_440))
        return saveRule(item, base, block, allowed)
    }

    /**
     * Saves a complete rule (blocking conditions included). The [block] flag decides whether a
     * blocked_application row exists at all; [allowed] decides the allowlist row. While a lock is
     * active the new rule must not be weaker than the old one (see [weakensWhileLocked]).
     *
     * Per the rebuild brief, per-app "Always Allow" is removed: callers pass allowed = false
     * (the profiles' Whitelist covers that use case), so this only ever clears the allowlist row.
     */
    suspend fun saveRule(
        item: AppItem,
        rule: BlockedApplication,
        block: Boolean,
        allowed: Boolean
    ): Boolean {
        val lockActive = Graph.lockManager.isActive()
        if (lockActive && weakensWhileLocked(item.rule, rule, block, allowed, item.allowed)) return false

        if (block && !item.alwaysAvailable) {
            Graph.db.blockedAppDao().upsert(rule.copy(packageName = item.packageName, label = item.label))
        } else {
            Graph.db.blockedAppDao().delete(item.packageName)
        }
        if (allowed) {
            Graph.db.allowedAppDao().upsert(AllowedApplication(item.packageName, item.label))
        } else {
            Graph.db.allowedAppDao().delete(item.packageName)
        }
        return true
    }

    /**
     * True when the new rule would loosen protection while a lock is active: removing the block,
     * switching off block-during-lock, raising a limit or launch cap, dropping a condition,
     * clearing the location/interval gate, shortening a quick block or wait, easing the goal,
     * or adding the app to the allowlist.
     */
    private fun weakensWhileLocked(
        old: BlockedApplication?,
        new: BlockedApplication,
        block: Boolean,
        allowed: Boolean,
        wasAllowed: Boolean
    ): Boolean {
        if (old == null) return allowed && !wasAllowed
        if (!block) return true
        if (old.blockDuringLock && !new.blockDuringLock) return true
        if (raises(old.dailyLimitMinutes, new.dailyLimitMinutes)) return true
        if (raises(old.hourlyLimitMinutes, new.hourlyLimitMinutes)) return true
        if (raises(old.maxLaunchesPerDay, new.maxLaunchesPerDay)) return true
        if (raises(old.maxLaunchesPerHour, new.maxLaunchesPerHour)) return true
        if (old.conditionType != "NONE" && new.conditionType == "NONE") return true
        if (old.locationZoneId > 0 && new.locationZoneId <= 0) return true
        if (old.fullDay && !new.fullDay && new.intervalStartMin == new.intervalEndMin) return true
        if (old.intervalStartMin != old.intervalEndMin && new.intervalStartMin == new.intervalEndMin && !new.fullDay) return true
        if (old.quickBlockUntilEpochMs > 0 && new.quickBlockUntilEpochMs < old.quickBlockUntilEpochMs) return true
        if (old.waitForMinutes > 0 && new.waitForMinutes < old.waitForMinutes) return true
        if (old.waitAfterMinutes > 0 && new.waitAfterMinutes > old.waitAfterMinutes) return true
        if (old.goalMinutes > 0 && (new.goalMinutes <= 0 || new.goalMinutes < old.goalMinutes)) return true
        if (old.blockLaunch && !new.blockLaunch) return true
        if (allowed && !wasAllowed) return true
        return false
    }

    /** A limit of 0 means "no limit" (the weakest), so raising means old > 0 and (new == 0 or new > old). */
    private fun raises(oldLimit: Int, newLimit: Int): Boolean =
        oldLimit > 0 && (newLimit == 0 || newLimit > oldLimit)
}
