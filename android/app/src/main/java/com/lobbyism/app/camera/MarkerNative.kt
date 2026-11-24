package com.lobbyism.app.camera

import android.util.Log
import java.nio.ByteBuffer

/**
 * Kotlin side of the native AprilTag detector (src/main/cpp/lobbyism_marker.c).
 *
 * Detection output per marker, [VALUES_PER_DETECTION] doubles:
 *   [0] id, [1] decision margin (higher = clearer),
 *   [2..10] R tag->camera (row-major 3x3), [11..13] t tag centre in camera coordinates (m).
 * Camera coordinates: x right, y down, z forward, raw sensor image.
 */
object MarkerNative {
    const val VALUES_PER_DETECTION = 14

    /** false if the native library could not be loaded (e.g. an unsupported CPU): markers are then off. */
    val isAvailable: Boolean = try {
        System.loadLibrary("lobbyism_marker")
        true
    } catch (e: Throwable) {
        Log.e("LOBBYISM/Marker", "native marker library not available", e)
        false
    }

    @JvmStatic external fun create(decimate: Float, threads: Int): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun detect(
        handle: Long,
        grey: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        tagSizeM: Double,
        out: DoubleArray,
    ): Int
}
