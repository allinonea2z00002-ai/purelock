package com.purelock.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.StatsCalculator
import com.purelock.app.data.StatsSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    /** Computed only from rows that were really recorded; an empty database shows zeros. */
    val summary: StateFlow<StatsSummary?> = Graph.db.statsDao().observeAll()
        .map { StatsCalculator.summarize(it, LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val completedSessions: StateFlow<Int> = Graph.db.sessionDao().observeCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
