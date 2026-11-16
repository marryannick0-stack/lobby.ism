package com.lobbyism.app.feedback

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central dispatcher for everything the player should FEEL.
 *
 * Game logic never vibrates, plays a sound or triggers a glitch directly. It
 * emits a [Cue] here, and the haptics, audio and renderer each react in their
 * own way. One event, three senses, always in sync. ("Build First" system 1.)
 */
object SensoryFeedback {
    enum class Cue {
        /** A node took shape in front of the player. */
        NODE_MATERIALIZED,
        /** The lock advanced another quarter. */
        LOCK_TICK,
        /** The lock snapped in. */
        LOCK_COMPLETE,
        /** A completed lock was lost. */
        LOCK_LOST,
        /** The player left a node in the world. */
        NODE_PLACED,
        /** Something could not be done. */
        DENIED,
    }

    fun interface Listener {
        /** @param intensity 0..1 */
        fun onCue(cue: Cue, intensity: Float)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** May be called from any thread; listeners must be thread-safe. */
    fun emit(cue: Cue, intensity: Float = 1f) {
        for (l in listeners) l.onCue(cue, intensity.coerceIn(0f, 1f))
    }
}
