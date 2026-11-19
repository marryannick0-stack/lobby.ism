package com.lobbyism.app.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Home base: private pins inside your own four walls.
 *
 * Indoors there is no GPS and, without camera tracking (stage 2), the phone
 * does not know where in the room it stands. It knows very precisely where it
 * POINTS, though. So the home base works with stations: fixed standing spots
 * ("couch", "desk"). From a station, pins hang in any direction around you.
 *
 * The gyro's zero direction is arbitrary and resets with every app start, so
 * each station has a reference mark (e.g. the light switch). Aiming at it and
 * tapping "align" restores the frame; every pin is stored relative to it.
 *
 * Limit: pins look right from the station's spot only. Walking through the
 * room moves them along with you. Free movement needs stage 2 or markers.
 *
 * Privacy (GDD: home is personal, never shown to others): stations and pins
 * stay on this phone and are never synced.
 */
data class HomeStation(
    val id: String,
    val name: String,
    /** What to aim at when aligning, in the player's own words. */
    val reference: String,
    val createdAtMs: Long,
)

data class HomePin(
    val id: String,
    val stationId: String,
    /** Horizontal angle clockwise from the reference mark, degrees (-180..180]. */
    val relAzimuthDeg: Double,
    /** Angle above (+) or below (-) the horizon, degrees. */
    val elevationDeg: Double,
    val distanceM: Double,
    val text: String,
    val createdAtMs: Long,
) {
    /** Node wrapper so pins share rendering, lock and content card with outdoor nodes. */
    fun asNode(): Node = Node(
        id = ID_PREFIX + id,
        lat = 0.0,
        lon = 0.0,
        contentType = ContentType.TEXT,
        payload = text,
        ownerId = null,
        status = ContentStatus.LIVE,
        createdAtMs = createdAtMs,
        synced = false,
    )

    companion object {
        const val ID_PREFIX = "home:"

        fun isHomeNodeId(nodeId: String) = nodeId.startsWith(ID_PREFIX)
        fun pinIdOf(nodeId: String) = nodeId.removePrefix(ID_PREFIX)
    }
}

/** Places the pins of one station in the sensor frame and runs the lock on them. */
class HomeTracker(private val cfg: Config) {

    private val locks = LockEngine(cfg)
    private val materialize = HashMap<String, Float>()

    /**
     * Sensor azimuth of the reference mark, measured at the last "align".
     * null = not aligned since the station was entered: pins cannot be placed or shown.
     */
    @Volatile var referenceAzimuthDeg: Double? = null

    val isAligned: Boolean get() = referenceAzimuthDeg != null

    /** Aligns the station: the player is aiming at the reference mark right now. */
    fun align(forward: FloatArray, top: FloatArray) {
        referenceAzimuthDeg = WorldTracker.sensorAzimuthDeg(WorldTracker.horizontalForward(forward, top))
    }

    fun leaveStation() {
        referenceAzimuthDeg = null
        materialize.clear()
        locks.retain(emptySet())
    }

    /**
     * Converts the current crosshair direction into a pin position relative to the
     * reference mark. Returns null while the station is not aligned.
     * @return [relAzimuthDeg, elevationDeg]
     */
    fun aimToPinAngles(forward: FloatArray): DoubleArray? {
        val ref = referenceAzimuthDeg ?: return null
        val len = sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2])
        if (len < 1e-6f) return null
        val elevation = Math.toDegrees(asin((forward[2] / len).toDouble().coerceIn(-1.0, 1.0)))
        // Straight up or down the horizontal direction is undefined; the angle then hardly matters.
        val azimuth = WorldTracker.sensorAzimuthDeg(forward)
        return doubleArrayOf(Geo.wrap180(azimuth - ref), elevation)
    }

    /** Lets a freshly placed pin appear without locking itself at once. */
    fun suppressLock(pin: HomePin) = locks.suppressUntilLeft(pin.asNode().id)

    fun update(dtS: Float, forward: FloatArray, pins: List<HomePin>): WorldTracker.Frame {
        val ref = referenceAzimuthDeg
        if (ref == null) return WorldTracker.Frame(emptyList(), null, 0, null, emptyList())

        val views = ArrayList<WorldTracker.NodeView>(pins.size)
        val events = ArrayList<WorldTracker.Event>()
        val seen = HashSet<String>()
        for (p in pins) {
            val node = p.asNode()
            seen.add(node.id)
            val m = ((materialize[node.id] ?: 0f) + dtS / MATERIALIZE_TIME_S).coerceAtMost(1f)
            materialize[node.id] = m
            views.add(
                WorldTracker.NodeView(
                    node, WorldTracker.Stage.MATERIALIZED, position(ref, p), p.distanceM,
                    1f, m, 0f, false, home = true,
                ),
            )
        }
        materialize.keys.retainAll(seen)
        locks.retain(seen)
        val focus = locks.update(dtS, forward, views, events)
        return WorldTracker.Frame(views, focus, 0, null, events)
    }

    private fun position(refAzimuthDeg: Double, p: HomePin): FloatArray {
        val az = Math.toRadians(refAzimuthDeg + p.relAzimuthDeg)
        val el = Math.toRadians(p.elevationDeg)
        val r = p.distanceM
        return floatArrayOf(
            (sin(az) * cos(el) * r).toFloat(),
            (cos(az) * cos(el) * r).toFloat(),
            (sin(el) * r).toFloat(),
        )
    }

    private companion object {
        const val MATERIALIZE_TIME_S = 0.8f
    }
}
