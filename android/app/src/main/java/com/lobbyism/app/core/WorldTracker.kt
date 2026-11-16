package com.lobbyism.app.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides, once per frame, where every nearby node appears and how far the
 * player's lock on it has progressed. Pure logic: no Android, no OpenGL.
 *
 * COORDINATE FRAME ("sensor frame", used for everything that gets rendered):
 *   origin = the camera, x/y = horizontal, z = up.
 *   The frame is the one of Android's game rotation vector: perfectly stable
 *   against rotation, but its horizontal zero direction is arbitrary.
 *   [HeadingEstimator] supplies the offset to true north once the player walked.
 *
 * WHY NODES GET "PINNED":
 *   GPS jumps by 4-28 m. Drawing a node 2 m away straight from GPS would make
 *   it leap around the player. So once the player is inside the materialise
 *   radius, the node is pinned to a fixed spot in the sensor frame and only the
 *   gyro moves it on screen. The node then stays put when the player looks
 *   away and back. GPS quality changes how much glitch is drawn (R1), never
 *   whether the node can be reached.
 */
class WorldTracker(private val cfg: Config) {

    enum class Stage { PILLAR, MATERIALIZED }

    /** What the renderer and the HUD need to know about one node this frame. */
    class NodeView(
        val node: Node,
        val stage: Stage,
        /** Position in the sensor frame, metres. For PILLAR: the base of the pillar. */
        val pos: FloatArray,
        val distanceM: Double,
        /** 0 = stable, 1 = pure noise. */
        val glitch: Float,
        /** 0 = scattered particles, 1 = fully assembled object. */
        val materialize: Float,
        /** 0..1 progress of the gyro lock. */
        val lock: Float,
        /** true while the lock is complete and the content is revealed. */
        val revealed: Boolean,
    )

    sealed class Event {
        data class Materialized(val node: Node) : Event()
        data class LockTick(val node: Node, val progress: Float) : Event()
        data class Locked(val node: Node) : Event()
        data class LockLost(val node: Node) : Event()
    }

    class Frame(
        val views: List<NodeView>,
        /** The node the player currently aims at, if any. */
        val focus: NodeView?,
        /** Nodes in range that cannot be drawn yet because north is unknown. */
        val hiddenUntilHeading: Int,
        val nearestDistanceM: Double?,
        val events: List<Event>,
    )

    private class State {
        var pin: FloatArray? = null
        var materialize = 0f
        var lock = 0f
        var revealed = false
        var lastTickStep = 0
    }

    private val states = HashMap<String, State>()

    /**
     * @param forward camera viewing direction in the sensor frame (unit vector)
     * @param top     direction of the phone's top edge in the sensor frame (unit vector)
     * @param headingOffsetDeg sensor azimuth + offset = true azimuth; null while north is unknown
     */
    fun update(
        dtS: Float,
        forward: FloatArray,
        top: FloatArray,
        userLat: Double?,
        userLon: Double?,
        gpsAccuracyM: Double,
        headingOffsetDeg: Double?,
        nodes: List<Node>,
    ): Frame {
        val events = ArrayList<Event>()
        val views = ArrayList<NodeView>()
        var hidden = 0
        var nearest: Double? = null

        if (userLat == null || userLon == null) {
            // No position: nothing can be placed. Keep states so pins survive a short GPS gap.
            return Frame(emptyList(), null, 0, null, events)
        }

        val radius = (cfg.materializeRadiusM + gpsAccuracyM)
            .coerceIn(cfg.materializeRadiusM, cfg.materializeRadiusMaxM)
        val seen = HashSet<String>()

        for (node in nodes) {
            if (node.status != ContentStatus.LIVE) continue
            val d = Geo.distanceM(userLat, userLon, node.lat, node.lon)
            if (d > cfg.scanRadiusM) continue
            seen.add(node.id)
            if (nearest == null || d < nearest) nearest = d

            val st = states.getOrPut(node.id) { State() }
            val bearing = Geo.bearingDeg(userLat, userLon, node.lat, node.lon)

            // --- Enter / leave the materialised state (with hysteresis) ---
            if (st.pin == null && d <= radius) {
                st.pin = choosePin(d, bearing, headingOffsetDeg, forward, top)
                events.add(Event.Materialized(node))
            } else if (st.pin != null && d > radius * cfg.materializeHysteresis) {
                st.pin = null
                st.revealed = false
                st.lock = 0f
            }

            val pin = st.pin
            if (pin != null) {
                st.materialize = (st.materialize + dtS / MATERIALIZE_TIME_S).coerceAtMost(1f)
                val glitch = (1f - 0.85f * st.lock).coerceIn(0.1f, 1f)
                views.add(NodeView(node, Stage.MATERIALIZED, pin, d, glitch, st.materialize, st.lock, st.revealed))
            } else {
                st.materialize = (st.materialize - dtS / MATERIALIZE_TIME_S).coerceAtLeast(0f)
                if (d <= cfg.pillarMaxDistanceM) {
                    if (headingOffsetDeg == null) {
                        hidden++
                    } else {
                        val dir = directionForBearing(bearing, headingOffsetDeg)
                        val r = d.coerceAtMost(PILLAR_RENDER_DISTANCE_M).toFloat()
                        val pos = floatArrayOf(dir[0] * r, dir[1] * r, -cfg.cameraHeightM.toFloat())
                        views.add(NodeView(node, Stage.PILLAR, pos, d, 1f, 0f, 0f, false))
                    }
                }
            }
        }
        // Forget nodes that vanished from the list or left the scan radius.
        states.keys.retainAll(seen)

        val focus = updateLock(dtS, forward, views, events)
        return Frame(views, focus, hidden, nearest, events)
    }

    /** Advances the lock on the node closest to the crosshair, decays all others. */
    private fun updateLock(
        dtS: Float,
        forward: FloatArray,
        views: ArrayList<NodeView>,
        events: ArrayList<Event>,
    ): NodeView? {
        var bestIndex = -1
        var bestErr = Double.MAX_VALUE
        for (i in views.indices) {
            val v = views[i]
            if (v.stage != Stage.MATERIALIZED || v.materialize < 0.8f) continue
            val err = angleBetweenDeg(forward, v.pos)
            if (err < bestErr) {
                bestErr = err
                bestIndex = i
            }
        }

        for (i in views.indices) {
            val v = views[i]
            if (v.stage != Stage.MATERIALIZED) continue
            val st = states[v.node.id] ?: continue
            val onTarget = i == bestIndex && bestErr <= cfg.lockAngleDeg
            val before = st.lock

            if (onTarget) {
                st.lock = (st.lock + dtS / cfg.lockTimeS.toFloat()).coerceAtMost(1f)
                // Haptic "ratchet": one tick per quarter of the way.
                val step = (st.lock * 4).toInt()
                if (step > st.lastTickStep && st.lock < 1f) events.add(Event.LockTick(v.node, st.lock))
                st.lastTickStep = step
                if (before < 1f && st.lock >= 1f) {
                    st.revealed = true
                    events.add(Event.Locked(v.node))
                }
            } else {
                st.lock = (st.lock - dtS / cfg.lockDecayS.toFloat()).coerceAtLeast(0f)
                st.lastTickStep = (st.lock * 4).toInt()
                if (st.revealed && st.lock <= 0f) {
                    st.revealed = false
                    events.add(Event.LockLost(v.node))
                }
            }
            // Replace the view so renderer and HUD see this frame's lock value.
            views[i] = NodeView(
                v.node, v.stage, v.pos, v.distanceM,
                (1f - 0.85f * st.lock).coerceIn(0.1f, 1f),
                v.materialize, st.lock, st.revealed,
            )
        }
        return if (bestIndex >= 0 && bestErr <= cfg.lockAngleDeg * FOCUS_CONE_FACTOR) views[bestIndex] else null
    }

    /**
     * Picks the spot where a node materialises.
     * With a known north and a meaningful distance, the GPS direction is used.
     * Otherwise (standing on top of it, or north unknown) it appears straight ahead.
     */
    private fun choosePin(
        distanceM: Double,
        bearingDeg: Double,
        headingOffsetDeg: Double?,
        forward: FloatArray,
        top: FloatArray,
    ): FloatArray {
        val z = (-cfg.cameraHeightM + cfg.nodeHoverM).toFloat()
        if (headingOffsetDeg != null && distanceM > cfg.pinDistanceMinM) {
            val dir = directionForBearing(bearingDeg, headingOffsetDeg)
            val r = distanceM.coerceIn(cfg.pinDistanceMinM, cfg.pinDistanceMaxM).toFloat()
            return floatArrayOf(dir[0] * r, dir[1] * r, z)
        }
        val dir = horizontalForward(forward, top)
        val r = cfg.pinDistanceM.toFloat()
        return floatArrayOf(dir[0] * r, dir[1] * r, z)
    }

    companion object {
        private const val MATERIALIZE_TIME_S = 1.2f
        private const val PILLAR_RENDER_DISTANCE_M = 60.0

        /** The HUD shows the focus bracket a bit before the lock actually charges. */
        private const val FOCUS_CONE_FACTOR = 2.5

        /** Azimuth of a vector in the sensor frame, degrees clockwise from the frame's y axis. */
        fun sensorAzimuthDeg(v: FloatArray): Double =
            Geo.wrap360(atan2(v[0].toDouble(), v[1].toDouble()) * 180.0 / PI)

        /** Horizontal unit vector in the sensor frame for a true bearing. */
        fun directionForBearing(trueBearingDeg: Double, headingOffsetDeg: Double): FloatArray {
            val a = (trueBearingDeg - headingOffsetDeg) * PI / 180.0
            return floatArrayOf(sin(a).toFloat(), cos(a).toFloat())
        }

        /**
         * Horizontal direction the player faces. If the phone looks steeply up or
         * down, the camera axis has no usable horizontal part; the top edge of the
         * phone then points where the player faces.
         */
        fun horizontalForward(forward: FloatArray, top: FloatArray): FloatArray {
            var x = forward[0]
            var y = forward[1]
            var len = sqrt(x * x + y * y)
            if (len < 0.35f) {
                // Looking down: top edge points ahead. Looking up: it points backwards.
                val sign = if (forward[2] < 0f) 1f else -1f
                x = top[0] * sign
                y = top[1] * sign
                len = sqrt(x * x + y * y)
            }
            if (len < 1e-4f) return floatArrayOf(0f, 1f)
            return floatArrayOf(x / len, y / len)
        }

        fun angleBetweenDeg(a: FloatArray, b: FloatArray): Double {
            val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (la < 1e-6f || lb < 1e-6f) return 180.0
            val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb)
            return acos(dot.toDouble().coerceIn(-1.0, 1.0)) * 180.0 / PI
        }
    }
}
