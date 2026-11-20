package com.purelock.app.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.blocklist.DomainMatcher
import com.purelock.app.data.db.BlockedDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sort orders from the All Sites sort popup (SPEC S09). */
enum class SiteSort { WEBSITE, BLOCKED, VISITS, USAGE, PINNED }

enum class AddDomainResult { ADDED, INVALID, EXISTS }

data class SiteItem(
    val domain: String,
    val blocked: Boolean,
    /** No per-domain visit store exists, so visits are 0 until one is added (see AllSitesActivity). */
    val visits: Int = 0,
    /** No per-domain usage store exists, so this is 0 until one is added. */
    val usageMs: Long = 0,
    val pinned: Boolean = false
)

/**
 * All Sites (SPEC S09): the user's own site list — the union of the custom blocklist
 * ([BlockedDomain]) and allowlist — sorted/filtered here. The bundled adult-domain list
 * is NOT shown: it has thousands of entries and is a filter source, not a user list.
 */
class AllSitesViewModel(app: Application) : AndroidViewModel(app) {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SiteSort.WEBSITE)
    private val pins = MutableStateFlow(PinStore.pinned(app, PinStore.KIND_SITES))

    val sortOrder: StateFlow<SiteSort> = sort

    /**
     * Domain -> blocked flag, from the two DAOs.
     * Kept as a 2-flow combine: the 5-flow combine failed overload resolution
     * (same SuspendFunctionN-vs-SuspendFunction1 error as AppListViewModel's
     * 7-flow combine, fixed by splitting into 4+4).
     */
    private val byDomain: Flow<Map<String, Boolean>> = combine(
        Graph.db.blockedDomainDao().observeAll(),
        Graph.db.allowedDomainDao().observeAll()
    ) { blocked, allowed ->
        val map = LinkedHashMap<String, Boolean>()
        blocked.forEach { map[it.domain] = true }
        allowed.forEach { if (it.domain !in map) map[it.domain] = false }
        map
    }

    val items: StateFlow<List<SiteItem>> = combine(
        byDomain, query, sort, pins
    ) { byDomainMap, q, sortOrder, pinned ->
        val filtered = byDomainMap.asSequence()
            .filter { q.isBlank() || it.key.contains(q, ignoreCase = true) }
            .map { (domain, isBlocked) ->
                SiteItem(domain = domain, blocked = isBlocked, pinned = domain in pinned)
            }
        when (sortOrder) {
            SiteSort.WEBSITE -> filtered.sortedBy { it.domain.lowercase() }
            SiteSort.BLOCKED -> filtered.sortedWith(
                compareByDescending<SiteItem> { it.blocked }.thenBy { it.domain.lowercase() }
            )
            SiteSort.VISITS -> filtered.sortedWith(
                compareByDescending<SiteItem> { it.visits }.thenBy { it.domain.lowercase() }
            )
            SiteSort.USAGE -> filtered.sortedWith(
                compareByDescending<SiteItem> { it.usageMs }.thenBy { it.domain.lowercase() }
            )
            SiteSort.PINNED -> filtered.sortedWith(
                compareByDescending<SiteItem> { it.pinned }.thenBy { it.domain.lowercase() }
            )
        }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(text: String) { query.value = text }

    fun setSort(order: SiteSort) { sort.value = order }

    /** Long-press a row to pin/unpin it. Returns the new pinned state. */
    fun togglePin(domain: String): Boolean {
        val nowPinned = domain !in pins.value
        PinStore.setPinned(getApplication(), PinStore.KIND_SITES, domain, nowPinned)
        pins.value = PinStore.pinned(getApplication(), PinStore.KIND_SITES)
        return nowPinned
    }

    /** Adds a domain to the blocklist (SPEC S09: typing a domain and confirming adds it). */
    fun addDomain(raw: String, onResult: (AddDomainResult) -> Unit) {
        viewModelScope.launch {
            val domain = DomainMatcher.sanitizeInput(raw)
            if (domain == null) { onResult(AddDomainResult.INVALID); return@launch }
            if (items.value.any { it.domain == domain }) { onResult(AddDomainResult.EXISTS); return@launch }
            Graph.db.blockedDomainDao().upsert(BlockedDomain(domain, System.currentTimeMillis()))
            Graph.blocklist.reload()
            onResult(AddDomainResult.ADDED)
        }
    }

    /**
     * Toggles a site's blocked state. Un-blocking a site weakens protection, so it is
     * refused while a lock is active (same rule as the old DomainListSheet).
     * Calls [onResult] with true when the toggle was applied.
     */
    fun toggleBlock(item: SiteItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (item.blocked && Graph.lockManager.isActive()) { onResult(false); return@launch }
            if (item.blocked) {
                Graph.db.blockedDomainDao().delete(item.domain)
            } else {
                // The allowlist always wins in the filter, so a blocked domain must
                // not stay allowed.
                Graph.db.allowedDomainDao().delete(item.domain)
                Graph.db.blockedDomainDao().upsert(BlockedDomain(item.domain, System.currentTimeMillis()))
            }
            Graph.blocklist.reload()
            onResult(true)
        }
    }
}
