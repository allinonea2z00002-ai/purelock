package com.purelock.app.ui.home

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.domain.LockEvaluator
import com.purelock.app.domain.LockManager
import com.purelock.app.domain.LockMode
import com.purelock.app.domain.LockSnapshot
import com.purelock.app.security.IntegrityReport
import com.purelock.app.vpn.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val snapshot: LockSnapshot,
    val settings: AppSettings,
    val vpnRunning: Boolean,
    val integrity: IntegrityReport,
    val accessibilityOn: Boolean,
    val blockedAppCount: Int
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private var finishing = false

    /** One-shot outcomes of starting / ending a test-mode trial lock. */
    sealed interface TestEvent {
        data object TestStarted : TestEvent
        /** A lock (real or test) is already running, so no test could start. */
        data object TestRefused : TestEvent
        data object TestEnded : TestEvent
    }

    private val _testEvents = MutableSharedFlow<TestEvent>(extraBufferCapacity = 8)
    val testEvents: SharedFlow<TestEvent> = _testEvents.asSharedFlow()

    /** Starts the 60-second test-mode trial lock. Refused when any lock is already active. */
    fun startTest() {
        viewModelScope.launch {
            val result = Graph.lockManager.startTest()
            _testEvents.emit(if (result is LockManager.StartResult.Started) TestEvent.TestStarted else TestEvent.TestRefused)
        }
    }

    /** Ends a running test lock at once. Does nothing when no test lock is running. */
    fun endTest() {
        viewModelScope.launch {
            if (Graph.lockManager.endTest()) _testEvents.emit(TestEvent.TestEnded)
        }
    }

    private val ticker = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }

    private val integrity = flow {
        while (true) {
            emit(Graph.integrity.check())
            delay(15_000)
        }
    }

    private val lockAndApps = combine(
        Graph.db.lockDao().observe(),
        Graph.db.blockedAppDao().observeAll()
    ) { lock, apps -> lock to apps.size }

    val state: StateFlow<HomeState?> = combine(
        lockAndApps, Graph.settings.flow, VpnState.running, integrity, ticker
    ) { (lock, appCount), settings, vpn, report, elapsed ->
        val snapshot = LockEvaluator.snapshot(
            lock, elapsed, System.currentTimeMillis(), Graph.lockManager.bootCount()
        )
        // The countdown reached zero (or the distance goal was met): let the engine finish the lock.
        if (snapshot.active && !finishing &&
            LockEvaluator.isUnlocked(snapshot.mode, snapshot.rule, snapshot.timeSatisfied, snapshot.distanceSatisfied)
        ) {
            finishing = true
            viewModelScope.launch {
                Graph.lockManager.checkpoint()
                finishing = false
            }
        }
        HomeState(
            snapshot = snapshot,
            settings = settings,
            vpnRunning = vpn,
            integrity = report,
            accessibilityOn = Graph.integrity.isAccessibilityEnabled(),
            blockedAppCount = appCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun usesLocation(snapshot: LockSnapshot) = snapshot.active && snapshot.mode != LockMode.TIME
}
