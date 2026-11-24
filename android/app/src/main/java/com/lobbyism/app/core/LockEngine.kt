package com.lobbyism.app.core

/**
 * The gyro lock, shared by the outdoor world and the home base.
 *
 * Each frame the object closest to the crosshair charges its lock while the
 * player holds still on it; every other object decays. Pure logic, no Android.
 */
class LockEngine(private val cfg: Config) {

    private class State {
        var lock = 0f
        var revealed = false
        var lastTickStep = 0
        /** Set right after placing: no lock until the crosshair has left the object once. */
        var suppressed = false
    }

    private val states = HashMap<String, State>()

    fun lockOf(id: String): Float = states[id]?.lock ?: 0f

    fun reset(id: String) {
        states.remove(id)
    }

    /** Forget everything not in [ids] (objects that disappeared). */
    fun retain(ids: Set<String>) {
        states.keys.retainAll(ids)
    }

    /**
     * Prevents an object that was just placed under the crosshair from locking
     * itself immediately. Lifts once the player looks away from it.
     */
    fun suppressUntilLeft(id: String) {
        states.getOrPut(id) { State() }.suppressed = true
    }

    /**
     * Advances all locks. Replaces entries of [views] with updated copies and
     * appends lock events to [events].
     * @return the object in the focus cone, or null
     */
    fun update(
        dtS: Float,
        forward: FloatArray,
        views: MutableList<WorldTracker.NodeView>,
        events: MutableList<WorldTracker.Event>,
    ): WorldTracker.NodeView? {
        var bestIndex = -1
        var bestErr = Double.MAX_VALUE
        for (i in views.indices) {
            val v = views[i]
            if (v.stage != WorldTracker.Stage.MATERIALIZED || v.materialize < 0.8f || !v.lockable) continue
            val err = WorldTracker.angleBetweenDeg(forward, v.pos)
            if (err < bestErr) {
                bestErr = err
                bestIndex = i
            }
        }

        for (i in views.indices) {
            val v = views[i]
            if (v.stage != WorldTracker.Stage.MATERIALIZED || !v.lockable) continue
            val st = states.getOrPut(v.node.id) { State() }
            val aimed = i == bestIndex && bestErr <= cfg.lockAngleDeg
            // Lift the suppression only once the object is fully there and the crosshair is off it;
            // during the fade-in it is not a lock candidate, which must not count as "looked away".
            if (st.suppressed && !aimed && v.materialize >= 0.8f) st.suppressed = false
            val onTarget = aimed && !st.suppressed
            val before = st.lock

            if (onTarget) {
                st.lock = (st.lock + dtS / cfg.lockTimeS.toFloat()).coerceAtMost(1f)
                // Haptic "ratchet": one tick per quarter of the way.
                val step = (st.lock * 4).toInt()
                if (step > st.lastTickStep && st.lock < 1f) events.add(WorldTracker.Event.LockTick(v.node, st.lock))
                st.lastTickStep = step
                if (before < 1f && st.lock >= 1f) {
                    st.revealed = true
                    events.add(WorldTracker.Event.Locked(v.node))
                }
            } else {
                st.lock = (st.lock - dtS / cfg.lockDecayS.toFloat()).coerceAtLeast(0f)
                st.lastTickStep = (st.lock * 4).toInt()
                if (st.revealed && st.lock <= 0f) {
                    st.revealed = false
                    events.add(WorldTracker.Event.LockLost(v.node))
                }
            }
            // Replace the view so renderer and HUD see this frame's lock value.
            views[i] = v.withLock(glitchFor(st.lock, v.baseGlitch), st.lock, st.revealed)
        }
        return if (bestIndex >= 0 && bestErr <= cfg.lockAngleDeg * FOCUS_CONE_FACTOR) views[bestIndex] else null
    }

    companion object {
        /** The HUD shows the focus bracket a bit before the lock actually charges. */
        private const val FOCUS_CONE_FACTOR = 2.5

        /** A lock steadies the object: a full lock removes 85 % of its resting glitch. */
        fun glitchFor(lock: Float, base: Float = 1f): Float = (base * (1f - 0.85f * lock)).coerceIn(0.05f, 1f)
    }
}
