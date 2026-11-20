package com.purelock.app.ui.home

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.prefs.AppSettings
import com.purelock.app.domain.LockEvaluator
import com.purelock.app.domain.LockMode
import com.purelock.app.domain.LockSnapshot
import com.purelock.app.security.IntegrityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeState(
    val snapshot: LockSnapshot,
    val settings: AppSettings,
    val integrity: IntegrityReport,
    val accessibilityOn: Boolean,
    val blockedAppCount: Int,
    /** All-time total of real app blocks recorded by the accessibility service. Never seeded. */
    val appBlocksTotal: Int,
    /** All-time total of real URL-watch website blocks. Never seeded; 0 until one really happens. */
    val websiteBlocksTotal: Int
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private var finishing = false

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

    /** Real block totals, summed over every stored per-day row. An empty database shows zeros. */
    private val blockTotals = Graph.db.statsDao().observeAll()
        .map { rows ->
            val summary = StatsCalculator.summarize(rows, LocalDate.now())
            summary.appBlocks to summary.websiteBlocks
        }

    val state: StateFlow<HomeState?> = combine(
        lockAndApps, Graph.settings.flow, blockTotals, integrity, ticker
    ) { (lock, appCount), settings, (appBlocks, websiteBlocks), report, elapsed ->
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
            integrity = report,
            accessibilityOn = Graph.integrity.isAccessibilityEnabled(),
            blockedAppCount = appCount,
            appBlocksTotal = appBlocks,
            websiteBlocksTotal = websiteBlocks
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun usesLocation(snapshot: LockSnapshot) = snapshot.active && snapshot.mode != LockMode.TIME
}
