package com.purelock.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Real, observable state of the filter tunnel. The UI never shows "active" unless this says so. */
object VpnState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile var lastError: String? = null
        internal set

    /** Epoch of the last DNS query handled; 0 if none since start. */
    @Volatile var lastQueryEpoch: Long = 0
        internal set

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }
}
