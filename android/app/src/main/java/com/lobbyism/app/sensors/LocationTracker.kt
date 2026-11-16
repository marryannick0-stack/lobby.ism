package com.lobbyism.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.lobbyism.app.core.Config

/**
 * GPS position straight from the satellite receiver.
 *
 * Uses LocationManager.GPS_PROVIDER only. That is the part of Android that is
 * open source and works without Google Play Services, so the position never
 * passes through a third-party location service.
 */
class LocationTracker(context: Context, private val cfg: Config) : LocationListener {

    /** Immutable snapshot of the latest fix. */
    class Fix(
        /** Smoothed position: calmer, used for everything on screen. */
        val lat: Double,
        val lon: Double,
        /** Raw position of the last fix: used when placing a node. */
        val rawLat: Double,
        val rawLon: Double,
        val accuracyM: Double,
        val speedMps: Double,
        val courseDeg: Double?,
        val courseAccuracyDeg: Double?,
        val elapsedRealtimeMs: Long,
    ) {
        /** A fix older than this is treated as "signal lost". */
        fun isFresh(): Boolean = SystemClock.elapsedRealtime() - elapsedRealtimeMs < 15_000
    }

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var fix: Fix? = null
        private set

    /** Called on the main thread for every new fix. */
    var onFix: ((Fix) -> Unit)? = null

    val isGpsEnabled: Boolean
        get() = try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, cfg.gpsIntervalMs, 0f, this, Looper.getMainLooper(),
            )
        } catch (e: IllegalArgumentException) {
            // Device without a GPS receiver: fix stays null and the HUD says so.
        } catch (e: SecurityException) {
            // Permission was revoked while running.
        }
    }

    fun stop() {
        manager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        val prev = fix
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0

        // Exponential smoothing; a fix after a long gap replaces the old position outright.
        val a = if (prev == null || !prev.isFresh()) 1.0 else cfg.positionSmoothing
        val lat = if (prev == null) location.latitude else prev.lat + (location.latitude - prev.lat) * a
        val lon = if (prev == null) location.longitude else prev.lon + (location.longitude - prev.lon) * a

        val next = Fix(
            lat = lat,
            lon = lon,
            rawLat = location.latitude,
            rawLon = location.longitude,
            accuracyM = acc,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            courseDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            courseAccuracyDeg = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
        fix = next
        onFix?.invoke(next)
    }

    // Required on Android versions below 11, where these are abstract.
    @Deprecated("Deprecated in Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
