package com.lobbyism.app.ui

/**
 * The latest phone pose, written by the GL thread every frame and read by the
 * UI thread when the player taps (to aim, align or pin). Fixed buffers: no
 * allocation per frame.
 */
class PoseCache {
    private val lock = Any()
    private val forward = FloatArray(3)
    private val top = FloatArray(3)
    private var valid = false

    /** GL thread. */
    fun write(f: FloatArray, t: FloatArray) {
        synchronized(lock) {
            System.arraycopy(f, 0, forward, 0, 3)
            System.arraycopy(t, 0, top, 0, 3)
            valid = true
        }
    }

    /** Any thread. @return copies of [forward, top], or null before the first sensor reading. */
    fun read(): Pair<FloatArray, FloatArray>? = synchronized(lock) {
        if (!valid) null else Pair(forward.copyOf(), top.copyOf())
    }
}
