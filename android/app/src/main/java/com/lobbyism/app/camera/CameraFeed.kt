package com.lobbyism.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
import com.lobbyism.app.core.CameraGeometry
import kotlin.math.abs
import kotlin.math.atan

/**
 * Live image of the back camera, delivered into an OpenGL texture.
 *
 * Uses Camera2, the camera API that is part of open-source Android. The preview
 * frames never leave the GPU. Optionally a second, grey stream feeds the marker
 * detector (home base); those frames are analysed in memory and released at once.
 * Nothing is stored or sent anywhere.
 */
class CameraFeed(context: Context, analysisTargetWidth: Int) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    /** The texture currently being streamed into; null while closed. */
    private var activeTexture: SurfaceTexture? = null

    /** The second (analysis) target of the open session, if any. */
    private var activeAnalysis: Surface? = null

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

    /** Size of the grey analysis stream (4:3 to use the whole sensor), or null if unsupported. */
    val analysisSize: Size?

    /** Pinhole intrinsics of the analysis stream, for marker pose estimation. */
    val analysisIntrinsics: CameraGeometry.Intrinsics?

    /** SENSOR_ORIENTATION of the back camera, degrees clockwise. */
    val sensorOrientationDeg: Int

    init {
        var size = Size(1280, 720)
        var fov = Math.toRadians(66.0).toFloat()
        var analysis: Size? = null
        var intrinsics: CameraGeometry.Intrinsics? = null
        var orientation = 90
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

                orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)
                if (yuv != null && yuv.isNotEmpty()) analysis = chooseAnalysisSize(yuv, analysisTargetWidth)
                val a = analysis
                // Calibration is given in pre-correction active array pixels (API 28+).
                val array = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    ?: ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (a != null && array != null && sensor != null && focal != null && focal.isNotEmpty()) {
                    intrinsics = CameraGeometry.intrinsics(
                        a.width, a.height, array.width(), array.height(),
                        ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
                        focal[0], sensor.width,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read camera characteristics", e)
        }
        previewSize = size
        fovLongSideRad = fov
        analysisSize = if (intrinsics != null) analysis else null
        analysisIntrinsics = intrinsics
        sensorOrientationDeg = orientation
    }

    /**
     * Starts streaming into [texture], and into [analysis] too if given.
     * Caller must hold the CAMERA permission. Safe to call repeatedly: the same
     * targets again do nothing, different targets close the old stream first.
     */
    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture, analysis: Surface?, fail: (String) -> Unit) {
        if (activeTexture === texture && activeAnalysis === analysis) return
        close()
        val id = cameraId ?: return fail("Keine Rückkamera gefunden")
        activeTexture = texture
        activeAnalysis = analysis
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
                    startPreview(camera, target, analysis, h, fail)
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
    private fun startPreview(camera: CameraDevice, target: Surface, analysis: Surface?, h: Handler, fail: (String) -> Unit) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                if (analysis != null) addTarget(analysis)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOfNotNull(target, analysis), object : CameraCaptureSession.StateCallback() {
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
        activeAnalysis = null
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

    /**
     * 4:3 like the sensor (no crop, full field of view), as close as possible to
     * the target width. Preview + YUV at preview size is a combination every
     * Camera2 device supports.
     */
    private fun chooseAnalysisSize(sizes: Array<Size>, targetWidth: Int): Size =
        sizes.minByOrNull {
            val aspectPenalty = abs(it.width.toFloat() / it.height - 4f / 3f) * 10_000f
            aspectPenalty + abs(it.width - targetWidth)
        } ?: sizes[0]

    private companion object {
        const val TAG = "LOBBYISM/Camera"
    }
}
