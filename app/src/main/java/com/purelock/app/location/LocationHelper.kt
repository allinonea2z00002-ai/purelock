package com.purelock.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** A plain location fix, independent of the Android Location class. */
data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Float, val epoch: Long)

/**
 * Location access built on the Fused Location Provider. Distances are computed with the Android
 * Location API ([Location.distanceBetween]); Google Maps is not needed.
 */
object LocationHelper {

    fun hasForegroundPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0]
    }

    /** One fresh fix, or null if permission/GPS is unavailable or no fix arrives within [timeoutMs]. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(context: Context, highAccuracy: Boolean, timeoutMs: Long = 15_000): Fix? {
        if (!hasForegroundPermission(context) || !isLocationEnabled(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val priority =
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val source = CancellationTokenSource()
        return try {
            val location = withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(priority, source.token).await()
            }
            location?.toFix()
        } catch (e: Exception) {
            null
        } finally {
            source.cancel()
        }
    }

    /** Live updates while a screen is visible. The callback is removed when the collector stops. */
    @SuppressLint("MissingPermission")
    fun updates(context: Context, intervalMs: Long = 10_000): Flow<Fix> = callbackFlow {
        if (!hasForegroundPermission(context)) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, context.mainLooper)
        } catch (e: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        epoch = System.currentTimeMillis()
    )
}
