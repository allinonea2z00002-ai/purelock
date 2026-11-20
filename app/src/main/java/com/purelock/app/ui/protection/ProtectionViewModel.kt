package com.purelock.app.ui.protection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.data.prefs.DnsProvider
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.domain.LockManager
import com.purelock.app.vpn.PureLockVpnService
import com.purelock.app.vpn.VpnState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface FilterUpdateState {
    data object Idle : FilterUpdateState
    data object Running : FilterUpdateState
    data class Done(val count: Int) : FilterUpdateState
    data class Failed(val message: String) : FilterUpdateState
}

sealed interface ProtectionEvent {
    data class StartResultEvent(val result: LockManager.StartResult) : ProtectionEvent
    data object EmergencyDone : ProtectionEvent
}

class ProtectionViewModel(app: Application) : AndroidViewModel(app) {

    val lock: StateFlow<LockConfiguration?> =
        Graph.db.lockDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> =
        Graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val vpnRunning: StateFlow<Boolean> = VpnState.running

    val blockedDomainCount: StateFlow<Int> = Graph.db.blockedDomainDao().observeAll()
        .map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val allowedDomainCount: StateFlow<Int> = Graph.db.allowedDomainDao().observeAll()
        .map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val blockedAppCount: StateFlow<Int> = Graph.db.blockedAppDao().observeAll()
        .map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _update = MutableStateFlow<FilterUpdateState>(FilterUpdateState.Idle)
    val update: StateFlow<FilterUpdateState> = _update.asStateFlow()

    private val _lastUpdate = MutableStateFlow<Pair<Long, Int>?>(null)
    val lastUpdate: StateFlow<Pair<Long, Int>?> = _lastUpdate.asStateFlow()

    private val _events = MutableSharedFlow<ProtectionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProtectionEvent> = _events.asSharedFlow()

    init {
        refreshFilterInfo()
    }

    fun refreshFilterInfo() {
        viewModelScope.launch { _lastUpdate.value = Graph.blocklist.lastUpdate() }
    }

    suspend fun isLockActive(): Boolean = Graph.lockManager.isActive()

    fun startLock(request: LockManager.StartRequest) {
        viewModelScope.launch {
            _events.emit(ProtectionEvent.StartResultEvent(Graph.lockManager.start(request)))
        }
    }

    fun emergencyOverride() {
        viewModelScope.launch {
            Graph.lockManager.emergencyOverride()
            _events.emit(ProtectionEvent.EmergencyDone)
        }
    }

    /** Starts or stops the standalone adult filter. Stopping is refused while a lock is active (the UI also says so). */
    fun setFilterEnabled(enabled: Boolean, onRefused: () -> Unit) {
        viewModelScope.launch {
            if (!enabled && Graph.lockManager.isActive()) {
                onRefused()
                return@launch
            }
            Graph.settings.set(SettingsKeys.FILTER_ENABLED, enabled)
            if (enabled) PureLockVpnService.start(getApplication()) else PureLockVpnService.stop(getApplication())
        }
    }

    fun setDnsProvider(provider: DnsProvider, onRefused: () -> Unit) {
        viewModelScope.launch {
            if (Graph.lockManager.isActive()) { onRefused(); return@launch }
            Graph.settings.set(SettingsKeys.DNS_PROVIDER, provider.name)
        }
    }

    fun setSafeSearch(enabled: Boolean, onRefused: () -> Unit) {
        viewModelScope.launch {
            if (!enabled && Graph.lockManager.isActive()) { onRefused(); return@launch }
            Graph.settings.set(SettingsKeys.SAFE_SEARCH, enabled)
        }
    }

    fun setUninstallProtection(enabled: Boolean, onRefused: () -> Unit) {
        viewModelScope.launch {
            if (!enabled && Graph.lockManager.isActive()) { onRefused(); return@launch }
            Graph.settings.set(SettingsKeys.UNINSTALL_PROTECTION, enabled)
        }
    }

    fun updateFilterList() {
        if (_update.value == FilterUpdateState.Running) return
        _update.value = FilterUpdateState.Running
        viewModelScope.launch {
            val url = Graph.settings.get().filterSourceUrl
            val result = Graph.blocklist.updateFromRemote(url)
            _update.value = result.fold(
                onSuccess = { FilterUpdateState.Done(it) },
                onFailure = { FilterUpdateState.Failed(it.message ?: it.javaClass.simpleName) }
            )
            refreshFilterInfo()
        }
    }
}
