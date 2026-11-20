package com.purelock.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.purelock.app.Graph
import com.purelock.app.data.db.SecurityEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (event.hasError()) {
                    Graph.eventLog.log(SecurityEventType.GEOFENCE_ERROR, "code=${event.errorCode}")
                    return@launch
                }
                val triggering = event.triggeringGeofences ?: emptyList()
                for (g in triggering) {
                    when {
                        g.requestId == GeofenceManager.LOCK_ID &&
                            event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                            Graph.lockManager.onGeofenceExit()

                        g.requestId.startsWith(GeofenceManager.ZONE_PREFIX) &&
                            event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER -> {
                            val id = g.requestId.removePrefix(GeofenceManager.ZONE_PREFIX).toLongOrNull()
                            if (id != null) Graph.lockManager.startZoneLock(id)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
