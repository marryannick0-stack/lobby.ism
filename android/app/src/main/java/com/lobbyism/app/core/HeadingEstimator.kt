package com.lobbyism.app.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds true north without a magnetometer (project rule R2: the compass is dead).
 *
 * The gyro-based rotation sensor gives a rock-solid orientation, but its zero
 * direction is arbitrary. GPS gives a true course over ground, but only while
 * the player walks. While walking, both describe the same direction, so their
 * difference is the constant offset between "sensor north" and true north.
 *
 * offset = gpsCourse - sensorAzimuth, averaged on the circle.
 *
 * Assumption: while walking the phone points roughly where the player walks.
 * Single samples violate this; the circular average and the spread check
 * absorb it. Until enough consistent samples exist, [isReady] stays false and
 * callers must not draw anything that depends on absolute direction.
 */
class HeadingEstimator(
    private val minSpeedMps: Double,
    private val maxCourseAccuracyDeg: Double,
    private val samplesNeeded: Int,
    private val smoothing: Double,
) {
    private var sumSin = 0.0
    private var sumCos = 0.0
    private var samples = 0

    /** Offset in degrees to add to a sensor azimuth to get a true azimuth. */
    val offsetDeg: Double
        get() = Geo.wrap360(atan2(sumSin, sumCos) * 180.0 / PI)

    /**
     * 0..1. Combines "enough samples" with "samples agree with each other"
     * (mean resultant length of the circular average).
     */
    val confidence: Double
        get() {
            if (samples == 0) return 0.0
            val agreement = sqrt(sumSin * sumSin + sumCos * sumCos)
            return (samples.toDouble() / samplesNeeded).coerceAtMost(1.0) * agreement
        }

    val isReady: Boolean
        get() = samples >= samplesNeeded && confidence > 0.6

    /**
     * Feed one GPS fix together with the sensor azimuth measured at the same time.
     * @return true if the sample was accepted.
     */
    fun addSample(
        gpsCourseDeg: Double,
        sensorAzimuthDeg: Double,
        speedMps: Double,
        courseAccuracyDeg: Double?,
    ): Boolean {
        if (speedMps < minSpeedMps) return false
        if (courseAccuracyDeg != null && courseAccuracyDeg > maxCourseAccuracyDeg) return false

        val diff = (gpsCourseDeg - sensorAzimuthDeg) * PI / 180.0
        if (samples == 0) {
            sumSin = sin(diff)
            sumCos = cos(diff)
        } else {
            // Exponential moving average on the unit circle: old headings fade,
            // which also follows the slow yaw drift of the gyro.
            sumSin += (sin(diff) - sumSin) * smoothing
            sumCos += (cos(diff) - sumCos) * smoothing
        }
        samples++
        return true
    }

    fun reset() {
        sumSin = 0.0
        sumCos = 0.0
        samples = 0
    }
}
