package com.lobbyism.app.camera

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.lobbyism.app.core.CameraGeometry
import com.lobbyism.app.core.Config
import com.lobbyism.app.sensors.OrientationTracker

/**
 * Finds printed AprilTag markers in a second, grey camera stream and reports
 * where they are in the render frame.
 *
 * Runs on its own thread at a limited rate. Frames are analysed in place and
 * released at once: nothing is stored or sent (privacy rule).
 *
 * Usage: [start] returns the Surface to add as a second camera target;
 * read [sightings] from any thread; [stop] after the camera was closed.
 */
class MarkerTracker(
    private val cfg: Config,
    private val camera: CameraFeed,
    private val orientation: OrientationTracker,
) {
    /** Latest result. Empty list = no marker in the last analysed frame. */
    class Sightings(val markers: List<CameraGeometry.MarkerPose>, val timeMs: Long)

    @Volatile var sightings = Sightings(emptyList(), 0L)
        private set

    /** Edge length of the printed black square, metres. Set from the settings. */
    @Volatile var markerSizeM: Double = cfg.markerSizeM

    val isAvailable: Boolean get() = MarkerNative.isAvailable && camera.analysisSize != null

    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handle = 0L
    private var lastRunMs = 0L

    // Reused per frame on the analysis thread.
    private val out = DoubleArray(MAX_MARKERS * MarkerNative.VALUES_PER_DETECTION)
    private val rotation = FloatArray(16)
    private val forward = FloatArray(3)
    private val top = FloatArray(3)
    private val camToDevice = CameraGeometry.cameraToDevice(camera.sensorOrientationDeg)

    /** @return the surface the camera must also stream into, or null if markers are unavailable. */
    fun start(): Surface? {
        if (!isAvailable) return null
        reader?.let { return it.surface }
        val size = camera.analysisSize ?: return null
        if (handle == 0L) handle = MarkerNative.create(cfg.markerDecimate.toFloat(), cfg.markerThreads)
        if (handle == 0L) return null

        val t = HandlerThread("lobbyism-marker").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ analyse(it) }, Handler(t.looper))
        reader = r
        return r.surface
    }

    /** Call after the camera session using the surface was closed. */
    fun stop() {
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        sightings = Sightings(emptyList(), 0L)
    }

    /** Frees the native detector. Call once when the screen is destroyed. */
    fun release() {
        stop()
        if (handle != 0L) {
            MarkerNative.destroy(handle)
            handle = 0L
        }
    }

    private fun analyse(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRunMs < 1000L / cfg.markerMaxFps) return
            lastRunMs = now
            // Attitude at (almost) the moment of the frame; the delay is a few ten milliseconds.
            if (!orientation.snapshot(rotation, forward, top)) return

            val k = camera.analysisIntrinsics ?: return
            val plane = image.planes[0] // Y = grey, pixel stride 1 by definition of YUV_420_888
            val n = MarkerNative.detect(
                handle, plane.buffer, image.width, image.height, plane.rowStride,
                k.fx, k.fy, k.cx, k.cy, markerSizeM, out,
            )
            val deviceToWorld = CameraGeometry.deviceToWorldFrom4x4(rotation)
            val found = ArrayList<CameraGeometry.MarkerPose>(n)
            for (i in 0 until n) {
                val o = i * MarkerNative.VALUES_PER_DETECTION
                if (out[o + 1] < cfg.markerMinMargin) continue // too unclear: likely a false detection
                found.add(
                    CameraGeometry.markerInWorld(
                        out[o].toInt(), out.copyOfRange(o + 2, o + 11), out.copyOfRange(o + 11, o + 14),
                        deviceToWorld, camToDevice,
                    ),
                )
            }
            sightings = Sightings(found, now)
        } catch (e: Exception) {
            Log.w(TAG, "marker analysis failed", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "LOBBYISM/Marker"
        const val MAX_MARKERS = 8
    }
}
