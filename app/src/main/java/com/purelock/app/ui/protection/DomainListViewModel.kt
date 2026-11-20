package com.purelock.app.ui.protection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.blocklist.DomainMatcher
import com.purelock.app.data.db.AllowedDomain
import com.purelock.app.data.db.BlockedDomain
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DomainResult { ADDED, INVALID, LOCKED, REMOVED }

/** Custom blacklist / allowlist. While a lock is active the filter may only get stricter. */
class DomainListViewModel(app: Application) : AndroidViewModel(app) {

    val blocked: StateFlow<List<String>> = Graph.db.blockedDomainDao().observeAll()
        .map { list -> list.map { it.domain } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allowed: StateFlow<List<String>> = Graph.db.allowedDomainDao().observeAll()
        .map { list -> list.map { it.domain } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(toBlacklist: Boolean, raw: String, onResult: (DomainResult) -> Unit) {
        viewModelScope.launch {
            val domain = DomainMatcher.sanitizeInput(raw)
            if (domain == null) { onResult(DomainResult.INVALID); return@launch }
            // Allowing a site weakens protection, so it is refused during a lock.
            if (!toBlacklist && Graph.lockManager.isActive()) { onResult(DomainResult.LOCKED); return@launch }
            val now = System.currentTimeMillis()
            if (toBlacklist) Graph.db.blockedDomainDao().upsert(BlockedDomain(domain, now))
            else Graph.db.allowedDomainDao().upsert(AllowedDomain(domain, now))
            Graph.blocklist.reload()
            onResult(DomainResult.ADDED)
        }
    }

    fun remove(fromBlacklist: Boolean, domain: String, onResult: (DomainResult) -> Unit) {
        viewModelScope.launch {
            // Un-blocking a site weakens protection, so it is refused during a lock.
            if (fromBlacklist && Graph.lockManager.isActive()) { onResult(DomainResult.LOCKED); return@launch }
            if (fromBlacklist) Graph.db.blockedDomainDao().delete(domain)
            else Graph.db.allowedDomainDao().delete(domain)
            Graph.blocklist.reload()
            onResult(DomainResult.REMOVED)
        }
    }
}
