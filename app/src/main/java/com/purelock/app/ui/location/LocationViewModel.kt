package com.purelock.app.ui.location

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purelock.app.Graph
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.db.SafeZone
import com.purelock.app.location.Fix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LocationUi(
    val lock: LockConfiguration?,
    val zones: List<SafeZone>,
    val historyCount: Int
)

class LocationViewModel(app: Application) : AndroidViewModel(app) {

    val ui: StateFlow<LocationUi> = combine(
        Graph.db.lockDao().observe(),
        Graph.db.safeZoneDao().observeAll(),
        Graph.db.locationDao().observeCount()
    ) { lock, zones, count -> LocationUi(lock, zones, count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationUi(null, emptyList(), 0))

    /** A real fix received while this screen is visible; the engine decides whether it matters. */
    fun feed(fix: Fix) {
        viewModelScope.launch {
            Graph.lockManager.onLocation(fix.latitude, fix.longitude, fix.accuracyM, "APP")
        }
    }

    fun addZone(name: String, fix: Fix, radiusM: Int, autoLockMinutes: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            Graph.db.safeZoneDao().upsert(
                SafeZone(
                    name = name.trim().take(60),
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    radiusM = radiusM,
                    autoLockMinutes = autoLockMinutes
                )
            )
            syncGeofences()
            onDone()
        }
    }

    fun deleteZone(id: Long) {
        viewModelScope.launch {
            Graph.db.safeZoneDao().delete(id)
            syncGeofences()
        }
    }

    fun deleteHistory() {
        viewModelScope.launch { Graph.db.locationDao().deleteAll() }
    }

    private suspend fun syncGeofences() {
        Graph.geofences.sync(Graph.db.lockDao().get(), Graph.db.safeZoneDao().getAll())
    }
}
