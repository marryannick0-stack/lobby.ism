package com.lobbyism.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan

/**
 * Live image of the back camera, delivered into an OpenGL texture.
 *
 * Uses Camera2, the camera API that is part of open-source Android. The frames
 * never leave the GPU: they are not stored, not analysed and not sent anywhere.
 */
class CameraFeed(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    /** The texture currently being streamed into; null while closed. */
    private var activeTexture: SurfaceTexture? = null

    /**
     * Counts open/close cycles. Camera callbacks arrive later on another thread;
     * a callback from an outdated cycle recognises itself by its number and backs off.
     */
    @Volatile private var generation = 0

    /** Id of the back camera, or null if the phone has none. */
    val cameraId: String? = findBackCamera()

    /** Size of the camera frames as the sensor delivers them (landscape: width > height). */
    val previewSize: Size

    /** Field of view along the LONG side of the camera frame, in radians. */
    val fovLongSideRad: Float

    init {
        var size = Size(1280, 720)
        var fov = Math.toRadians(66.0).toFloat()
        try {
            val id = cameraId
            if (id != null) {
                val ch = manager.getCameraCharacteristics(id)
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                if (sizes != null && sizes.isNotEmpty()) size = chooseSize(sizes)

                // FOV from the physical lens data, so virtual objects line up with the real image.
                val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (sensor != null && focal != null && focal.isNotEmpty() && focal[0] > 0f) {
                    val longSideMm = maxOf(sensor.width, sensor.height)
                    val f = 2f * atan(longSideMm / (2f * focal[0]))
                    // Guard against nonsense values reported by some devices.
                    if (f > Math.toRadians(30.0) && f < Math.toRadians(120.0)) fov = f
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read camera characteristics", e)
        }
        previewSize = size
        fovLongSideRad = fov
    }

    /**
     * Starts streaming into [texture]. Caller must hold the CAMERA permission.
     * Safe to call repeatedly: a second call with the same texture does nothing,
     * a call with a new texture closes the old stream first.
     */
    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture, fail: (String) -> Unit) {
        if (activeTexture === texture) return
        close()
        val id = cameraId ?: return fail("Keine Rückkamera gefunden")
        activeTexture = texture
        val gen = ++generation

        val t = HandlerThread("lobbyism-camera").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val target = Surface(texture)
        surface = target

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) {
                        camera.close() // closed or reopened in the meantime
                        return
                    }
                    device = camera
                    startPreview(camera, target, h, fail)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    if (gen == generation) {
                        activeTexture = null // allow a retry
                        fail("Kamera-Fehler $error")
                    }
                }
            }, h)
        } catch (e: Exception) {
            activeTexture = null
            fail("Kamera lässt sich nicht öffnen: ${e.message}")
        }
    }

    @Suppress("DEPRECATION") // The list-based createCaptureSession is the variant available on API 26.
    private fun startPreview(camera: CameraDevice, target: Surface, h: Handler, fail: (String) -> Unit) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== camera) return
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, h)
                    } catch (e: Exception) {
                        fail("Kamera-Vorschau startet nicht: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    fail("Kamera-Konfiguration fehlgeschlagen")
                }
            }, h)
        } catch (e: Exception) {
            fail("Kamera-Vorschau startet nicht: ${e.message}")
        }
    }

    fun close() {
        generation++
        activeTexture = null
        try {
            session?.close()
        } catch (e: Exception) {
            // Already closed by the system.
        }
        session = null
        device?.close()
        device = null
        surface?.release()
        surface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun findBackCamera(): String? = try {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        null
    }

    /** Prefers 16:9 at up to 1280x720: enough for a background, cheap for old phones. */
    private fun chooseSize(sizes: Array<Size>): Size {
        val wantedAspect = 16f / 9f
        return sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull {
                val aspectPenalty = abs(it.width.toFloat() / it.height - wantedAspect) * 10_000f
                aspectPenalty + abs(it.width - 1280)
            } ?: sizes[0]
    }

    private companion object {
        const val TAG = "LOBBYISM/Camera"
    }
}
