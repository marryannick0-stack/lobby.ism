package com.lobbyism.app.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Home base: private objects inside your own four walls.
 *
 * Indoors there is no GPS and, without camera tracking (stage 2), the phone
 * does not know where in the room it stands. It knows very precisely where it
 * POINTS, though. So the home base works with stations: fixed standing spots
 * ("couch", "desk"). From a station, objects sit in any direction around you.
 *
 * The gyro's zero direction is arbitrary and resets with every app start, so
 * each station has a reference mark (e.g. the light switch). Aiming at it and
 * tapping "align" restores the frame; every object is stored relative to it.
 *
 * Two kinds of objects:
 *  - NOTE: hangs where the crosshair pointed, at a fixed distance (on a wall, the ceiling).
 *  - GEAR: an armour stand that stands ON THE FLOOR. Aim at the floor spot; the
 *    distance follows from the phone's height and the downward angle (virtual ground).
 *
 * Limit: objects look right from the station's spot only. Walking through the
 * room moves them along with you. Free movement needs stage 2 or markers.
 *
 * Privacy (GDD: home is personal, never shown to others): everything here
 * stays on this phone and is never synced.
 */
data class HomeStation(
    val id: String,
    val name: String,
    /** What to aim at when aligning, in the player's own words. */
    val reference: String,
    val createdAtMs: Long,
)

enum class HomeObjectKind { NOTE, GEAR }

/** Where an object sits, relative to its station's reference mark. */
data class Placement(
    /** Horizontal angle clockwise from the reference mark, degrees (-180..180]. */
    val relAzimuthDeg: Double,
    /** NOTE: angle above (+) or below (-) the horizon. GEAR: unused (0). */
    val elevationDeg: Double,
    /** NOTE: straight-line distance. GEAR: horizontal distance to the stand on the floor. */
    val distanceM: Double,
)

data class HomeObject(
    val id: String,
    val stationId: String,
    val kind: HomeObjectKind,
    val placement: Placement,
    /** NOTE: the text. GEAR: empty. */
    val text: String,
    /** GEAR: the stash item shown on the stand. */
    val gear: GearItem?,
    val createdAtMs: Long,
) {
    /** Node wrapper so objects share lock and content card with outdoor nodes. */
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

        /** Id used for the object currently being carried (not yet stored). */
        const val CARRY_ID = "carry"

        fun isHomeNodeId(nodeId: String) = nodeId.startsWith(ID_PREFIX)
        fun objectIdOf(nodeId: String) = nodeId.removePrefix(ID_PREFIX)
    }
}

/**
 * Places the objects of one station in the sensor frame, runs the lock on
 * them and handles the carried object that follows the crosshair.
 */
class HomeTracker(private val cfg: Config) {

    private val locks = LockEngine(cfg)
    private val materialize = HashMap<String, Float>()

    /**
     * Sensor azimuth of the reference mark, measured at the last "align".
     * null = not aligned since the station was entered: nothing can be placed or shown.
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
     * Where an object of [kind] would go if placed along the crosshair now.
     * Returns null while the station is not aligned.
     */
    fun placementFromAim(kind: HomeObjectKind, forward: FloatArray): Placement? {
        val ref = referenceAzimuthDeg ?: return null
        val len = sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2])
        if (len < 1e-6f) return null
        val elevation = Math.toDegrees(asin((forward[2] / len).toDouble().coerceIn(-1.0, 1.0)))
        // Straight up or down the horizontal direction is undefined; the angle then hardly matters.
        val relAz = Geo.wrap180(WorldTracker.sensorAzimuthDeg(forward) - ref)
        return when (kind) {
            HomeObjectKind.NOTE -> Placement(relAz, elevation, cfg.homePinDistanceM)
            HomeObjectKind.GEAR -> Placement(relAz, 0.0, floorDistance(elevation))
        }
    }

    /**
     * Distance to the floor point the crosshair hits (virtual ground: the floor
     * lies [Config.cameraHeightM] below the phone). Looking level or up has no
     * floor hit; the stand then goes to the default distance.
     */
    fun floorDistance(elevationDeg: Double): Double {
        val depression = -elevationDeg
        if (depression < MIN_FLOOR_DEPRESSION_DEG) return cfg.homeGearDefaultDistanceM
        val d = cfg.cameraHeightM / tan(Math.toRadians(depression))
        return d.coerceIn(cfg.homeGearMinDistanceM, cfg.homeGearMaxDistanceM)
    }

    /** Lets a freshly placed object appear without locking itself at once. */
    fun suppressLock(obj: HomeObject) = locks.suppressUntilLeft(obj.asNode().id)

    /**
     * @param carried the object following the crosshair (being placed or moved), or null.
     *   It is drawn at the aimed spot, never locks, and replaces a stored object with the same id.
     */
    fun update(dtS: Float, forward: FloatArray, objects: List<HomeObject>, carried: HomeObject?): WorldTracker.Frame {
        val ref = referenceAzimuthDeg ?: return WorldTracker.Frame(emptyList(), null, 0, null, emptyList())

        val views = ArrayList<WorldTracker.NodeView>(objects.size + 1)
        val events = ArrayList<WorldTracker.Event>()
        val seen = HashSet<String>()
        for (o in objects) {
            if (carried != null && o.id == carried.id) continue // shown at the crosshair instead
            val node = o.asNode()
            seen.add(node.id)
            val m = ((materialize[node.id] ?: 0f) + dtS / MATERIALIZE_TIME_S).coerceAtMost(1f)
            materialize[node.id] = m
            views.add(view(ref, o, o.placement, m, lockable = true))
        }
        if (carried != null) {
            placementFromAim(carried.kind, forward)?.let { views.add(view(ref, carried, it, 1f, lockable = false)) }
        }
        materialize.keys.retainAll(seen)
        locks.retain(seen)
        val focus = locks.update(dtS, forward, views, events)
        return WorldTracker.Frame(views, focus, 0, null, events)
    }

    private fun view(ref: Double, o: HomeObject, p: Placement, materialized: Float, lockable: Boolean): WorldTracker.NodeView {
        val az = ref + p.relAzimuthDeg
        val pos: FloatArray
        val yaw: Float
        val baseGlitch: Float
        when (o.kind) {
            HomeObjectKind.NOTE -> {
                pos = spherical(az, p.elevationDeg, p.distanceM)
                yaw = 0f
                baseGlitch = 1f
            }
            HomeObjectKind.GEAR -> {
                // Centre of the stand: standing on the virtual floor, half its height up.
                val a = Math.toRadians(az)
                val centreZ = -cfg.cameraHeightM + cfg.gearStandHeightM / 2.0
                pos = floatArrayOf(
                    (sin(a) * p.distanceM).toFloat(),
                    (cos(a) * p.distanceM).toFloat(),
                    centreZ.toFloat(),
                )
                // The stand turns its front towards the station (the player).
                yaw = Geo.wrap360(az + 180.0).toFloat()
                baseGlitch = o.gear?.let { tierGlitch(it.tier) } ?: 1f
            }
        }
        return WorldTracker.NodeView(
            o.asNode(), WorldTracker.Stage.MATERIALIZED, pos, p.distanceM,
            baseGlitch, materialized, 0f, false,
            home = true, gear = o.gear, yawDeg = yaw, lockable = lockable, baseGlitch = baseGlitch,
        )
    }

    /** Glitch of a resting stand by tier (Art Constraints 3: T1 noise ... T4 presence). */
    fun tierGlitch(tier: GearTier): Float =
        cfg.gearTierGlitch.getOrElse(tier.ordinal) { 1.0 }.toFloat().coerceIn(0.05f, 1f)

    private fun spherical(azDeg: Double, elDeg: Double, r: Double): FloatArray {
        val az = Math.toRadians(azDeg)
        val el = Math.toRadians(elDeg)
        return floatArrayOf(
            (sin(az) * cos(el) * r).toFloat(),
            (cos(az) * cos(el) * r).toFloat(),
            (sin(el) * r).toFloat(),
        )
    }

    private companion object {
        const val MATERIALIZE_TIME_S = 0.8f
        /** Below this downward angle the crosshair does not hit the floor in a sensible distance. */
        const val MIN_FLOOR_DEPRESSION_DEG = 12.0
    }
}
