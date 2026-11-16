package com.lobbyism.app.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesy helpers. Pure math, no Android imports, so it runs in plain JVM unit tests.
 *
 * Conventions used across the whole client:
 *  - Bearings/azimuths are degrees clockwise from true north, range [0, 360).
 *  - Local offsets are metres east / metres north of an origin.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG
        val p2 = lat2 * DEG
        val dLon = (lon2 - lon1) * DEG
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return wrap360(atan2(y, x) / DEG)
    }

    /**
     * East/north offset in metres of (lat, lon) relative to an origin.
     * Equirectangular approximation: error is far below GPS noise for the
     * few hundred metres this client ever looks at.
     */
    fun enuOffsetM(originLat: Double, originLon: Double, lat: Double, lon: Double): DoubleArray {
        val east = (lon - originLon) * DEG * EARTH_RADIUS_M * cos(originLat * DEG)
        val north = (lat - originLat) * DEG * EARTH_RADIUS_M
        return doubleArrayOf(east, north)
    }

    /** Wraps any angle into [0, 360). */
    fun wrap360(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Wraps any angle into (-180, 180]. */
    fun wrap180(deg: Double): Double {
        val r = wrap360(deg)
        return if (r > 180.0) r - 360.0 else r
    }
}
