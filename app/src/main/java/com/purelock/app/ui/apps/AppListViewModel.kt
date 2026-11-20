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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val label: String,
    val rule: BlockedApplication?,
    val allowed: Boolean,
    val usageTodayMs: Long,
    /** Phone/launcher/system apps can never be blocked. */
    val alwaysAvailable: Boolean
)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private data class Installed(val packageName: String, val label: String)

    private val installed = MutableStateFlow<List<Installed>>(emptyList())
    private val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val query = MutableStateFlow("")
    private val policy = AppBlockPolicy(app, Graph.usage)

    val loading = MutableStateFlow(true)

    private val lists = combine(
        Graph.db.blockedAppDao().observeAll(),
        Graph.db.allowedAppDao().observeAll()
    ) { blocked, allowed -> blocked.associateBy { it.packageName } to allowed.map { it.packageName }.toSet() }

    val items: StateFlow<List<AppItem>> = combine(installed, query, lists, usage) { apps, q, (blocked, allowed), usageMap ->
        apps.asSequence()
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .map {
                AppItem(
                    packageName = it.packageName,
                    label = it.label,
                    rule = blocked[it.packageName],
                    allowed = it.packageName in allowed,
                    usageTodayMs = usageMap[it.packageName] ?: 0L,
                    alwaysAvailable = policy.isNeverBlocked(it.packageName)
                )
            }
            // blocked apps first, then alphabetical
            .sortedWith(compareByDescending<AppItem> { it.rule != null }.thenBy { it.label.lowercase() })
            .toList()
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
            loading.value = false
        }
    }

    fun setQuery(text: String) { query.value = text }

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
        val lockActive = Graph.lockManager.isActive()
        if (lockActive) {
            val old = item.rule
            val removesBlock = old != null && !block
            val weakensLock = old != null && old.blockDuringLock && !blockDuringLock
            val raisesLimit = old != null && old.dailyLimitMinutes > 0 &&
                (limitMinutes == 0 || limitMinutes > old.dailyLimitMinutes)
            val addsAllow = allowed && !item.allowed
            if (removesBlock || weakensLock || raisesLimit || addsAllow) return false
        }

        if (block && !item.alwaysAvailable) {
            Graph.db.blockedAppDao().upsert(
                BlockedApplication(item.packageName, item.label, blockDuringLock, limitMinutes.coerceIn(0, 1_440))
            )
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
}
