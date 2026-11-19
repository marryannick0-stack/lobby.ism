package com.lobbyism.app.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Size
import com.lobbyism.app.core.Config
import com.lobbyism.app.core.WorldTracker
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.sensors.OrientationTracker
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.tan

/**
 * The LOBBYISM micro engine: draws one frame in three passes.
 *
 *   1. Camera image, full screen, untouched.
 *   2. All LOBBYISM objects as point clouds into an offscreen layer
 *      (smaller than the screen: cheap, and the softness suits the look).
 *   3. The layer over the camera image through the signal-degradation shader.
 *
 * Runs on the GL thread. Everything it reads from other threads is either
 * volatile or copied under a lock.
 */
class ArRenderer(
    private val cfg: Config,
    private val orientation: OrientationTracker,
    private val previewSize: Size,
    private val fovLongSideRad: Float,
    private val scene: Scene,
    /** Called on the GL thread when the camera texture exists (again). */
    private val onCameraTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer, SensoryFeedback.Listener {

    /** Supplies what to draw. Called once per frame on the GL thread. */
    fun interface Scene {
        fun frame(dtS: Float, poseValid: Boolean, forward: FloatArray, top: FloatArray): List<WorldTracker.NodeView>
    }

    /** Extra rotation of the camera image in 90 degree steps (debug escape hatch for odd devices). */
    @Volatile var cameraRotationSteps = 0

    @Volatile private var pulse = 0f

    // --- GL objects ---
    private var cameraProgram = 0
    private var pointsProgram = 0
    private var compositeProgram = 0
    private var cameraTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var nodeBuffer = 0
    private var nodeVertexCount = 0
    private var pillarBuffer = 0
    private var pillarVertexCount = 0
    private var layerFbo = 0
    private var layerTexture = 0
    private var layerW = 1
    private var layerH = 1
    private var screenW = 1
    private var screenH = 1
    private val quad: FloatBuffer = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    // --- per-frame scratch (allocated once: no garbage while rendering) ---
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val tmp = FloatArray(16)
    private val forward = FloatArray(3)
    private val top = FloatArray(3)
    private var lastFrameMs = 0L
    private var startMs = 0L
    private var fovYRad = 1.1f
    private var cropX = 1f
    private var cropY = 1f
    private var appliedRotationSteps = -1

    override fun onCue(cue: SensoryFeedback.Cue, intensity: Float) {
        // Event-based glitch (art brief: effects are triggered, never permanent).
        pulse = when (cue) {
            SensoryFeedback.Cue.LOCK_COMPLETE, SensoryFeedback.Cue.NODE_PLACED -> 1f
            SensoryFeedback.Cue.NODE_MATERIALIZED -> 0.8f
            SensoryFeedback.Cue.LOCK_LOST, SensoryFeedback.Cue.DENIED -> 0.5f
            SensoryFeedback.Cue.LOCK_TICK -> maxOf(pulse, 0.15f)
            SensoryFeedback.Cue.STEP_DONE -> maxOf(pulse, 0.4f)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: every object from before is gone and must be rebuilt.
        cameraProgram = GlUtil.program(Shaders.CAMERA_VS, Shaders.CAMERA_FS)
        pointsProgram = GlUtil.program(Shaders.POINTS_VS, Shaders.POINTS_FS)
        compositeProgram = GlUtil.program(Shaders.COMPOSITE_VS, Shaders.COMPOSITE_FS)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture?.release()
        val st = SurfaceTexture(cameraTexture)
        surfaceTexture = st
        Matrix.setIdentityM(stMatrix, 0)

        val nodeData = NodeGeometry.node(cfg.nodePointCount)
        nodeVertexCount = nodeData.size / NodeGeometry.FLOATS_PER_VERTEX
        nodeBuffer = GlUtil.staticBuffer(nodeData)
        val pillarData = NodeGeometry.pillar(PILLAR_POINTS, PILLAR_HEIGHT_M)
        pillarVertexCount = pillarData.size / NodeGeometry.FLOATS_PER_VERTEX
        pillarBuffer = GlUtil.staticBuffer(pillarData)

        layerFbo = 0
        layerTexture = 0
        startMs = SystemClock.elapsedRealtime()
        lastFrameMs = startMs
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        onCameraTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = maxOf(1, width)
        screenH = maxOf(1, height)
        layerW = maxOf(1, (screenW * cfg.layerScale).toInt())
        layerH = maxOf(1, (screenH * cfg.layerScale).toInt())
        createLayer()
        appliedRotationSteps = -1 // forces updateCameraMapping() on the next frame
    }

    /** (Re)creates the offscreen layer the LOBBYISM objects are drawn into. */
    private fun createLayer() {
        if (layerTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(layerTexture), 0)
        if (layerFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(layerFbo), 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        layerTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, layerW, layerH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 0)
        layerFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, layerFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, layerTexture, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Works out how the camera image is cropped to fill the screen and which
     * field of view the virtual camera therefore needs. Both must match, or
     * virtual objects would not line up with the real image.
     */
    private fun updateCameraMapping() {
        val steps = cameraRotationSteps
        appliedRotationSteps = steps
        val longSide = maxOf(previewSize.width, previewSize.height).toFloat()
        val shortSide = minOf(previewSize.width, previewSize.height).toFloat()

        // The system rotates the camera frame upright for a portrait phone:
        // on screen it is "short side wide, long side tall". An odd number of
        // extra rotation steps swaps that.
        val upright = steps % 2 == 0
        val imageAspect = if (upright) shortSide / longSide else longSide / shortSide
        val screenAspect = screenW.toFloat() / screenH

        if (screenAspect < imageAspect) {
            cropX = screenAspect / imageAspect // screen is narrower: cut left and right
            cropY = 1f
        } else {
            cropX = 1f
            cropY = imageAspect / screenAspect // screen is wider: cut top and bottom
        }

        val tanHalfLong = tan(fovLongSideRad / 2f)
        val tanHalfVertical = if (upright) tanHalfLong else tanHalfLong * shortSide / longSide
        fovYRad = 2f * atan(tanHalfVertical * cropY)
        Matrix.perspectiveM(proj, 0, Math.toDegrees(fovYRad.toDouble()).toFloat(), screenAspect, 0.1f, 500f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        // Wrapped so the shaders' sin() never sees huge numbers (precision on old GPUs).
        val time = ((now - startMs) % 600_000L) / 1000f

        if (appliedRotationSteps != cameraRotationSteps) updateCameraMapping()

        // --- pass 1: camera ---
        val st = surfaceTexture
        if (st != null) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            } catch (e: RuntimeException) {
                // No frame yet or the camera is closing: keep the previous image.
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCamera()

        // --- world state for this frame ---
        val poseValid = orientation.snapshot(view, forward, top)
        val views = scene.frame(dt, poseValid, forward, top)

        // --- pass 2: LOBBYISM objects into the layer ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, layerFbo)
        GLES20.glViewport(0, 0, layerW, layerH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (poseValid && views.isNotEmpty() && pointsProgram != 0) {
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            drawPoints(views, time)
        }

        // --- pass 3: layer over camera ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        drawComposite(time)

        pulse = (pulse - dt / PULSE_DECAY_S).coerceAtLeast(0f)
    }

    private fun drawCamera() {
        if (cameraProgram == 0) return
        // Screen coordinate (0..1) -> crop around the centre -> optional rotation -> system transform.
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(tmp, 0, 90f * appliedRotationSteps, 0f, 0f, 1f)
        Matrix.scaleM(tmp, 0, cropX, cropY, 1f)
        Matrix.translateM(tmp, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, tmp, 0)

        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "uCamera"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        drawQuad(cameraProgram)
    }

    private fun drawPoints(views: List<WorldTracker.NodeView>, time: Float) {
        val p = pointsProgram
        GLES20.glUseProgram(p)
        // Additive light: overlapping points get brighter. Alpha accumulates for the composite pass.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE)

        val aPos = GLES20.glGetAttribLocation(p, "aPos")
        val aSeed = GLES20.glGetAttribLocation(p, "aSeed")
        val uMvp = GLES20.glGetUniformLocation(p, "uMvp")
        val uGlitch = GLES20.glGetUniformLocation(p, "uGlitch")
        val uMaterialize = GLES20.glGetUniformLocation(p, "uMaterialize")
        val uPointSize = GLES20.glGetUniformLocation(p, "uPointSize")
        val uLock = GLES20.glGetUniformLocation(p, "uLock")
        val uColor = GLES20.glGetUniformLocation(p, "uColor")
        val uPulseAmount = GLES20.glGetUniformLocation(p, "uPulseAmount")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), time)

        // Size in pixels of a 1 m object at 1 m distance, for perspective point sizes.
        val pixelsPerMetre = layerH / (2f * tan(fovYRad / 2f))
        val stride = NodeGeometry.FLOATS_PER_VERTEX * 4

        for (v in views) {
            val isNode = v.stage == WorldTracker.Stage.MATERIALIZED
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, if (isNode) nodeBuffer else pillarBuffer)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(aSeed)
            GLES20.glVertexAttribPointer(aSeed, 1, GLES20.GL_FLOAT, false, stride, 12)

            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, v.pos[0], v.pos[1], v.pos[2])
            if (isNode) {
                // The node stays in place; only its fragments turn slowly around the vertical axis.
                // Home pins turn slower still: personal, static (GDD: home = warm and calm).
                val spin = if (v.home) HOME_SPIN_DEG_PER_S else NODE_SPIN_DEG_PER_S
                Matrix.rotateM(model, 0, time * spin, 0f, 0f, 1f)
                val s = (if (v.home) cfg.homeNodeSizeM else cfg.nodeSizeM).toFloat()
                Matrix.scaleM(model, 0, s, s, s)
            }
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(uGlitch, v.glitch)
            GLES20.glUniform1f(uMaterialize, if (isNode) v.materialize else 1f)
            GLES20.glUniform1f(uLock, v.lock)
            GLES20.glUniform1f(uPointSize, pixelsPerMetre * (if (isNode) NODE_POINT_M else PILLAR_POINT_M))
            // Pale phosphor, like an old sonar tube. Deliberately not neon.
            // Home pins: warm amber. Told apart by colour AND by a calmer pulse (art constraint 12).
            when {
                v.home -> GLES20.glUniform3f(uColor, 1.00f, 0.80f, 0.55f)
                isNode -> GLES20.glUniform3f(uColor, 0.80f, 1.00f, 0.86f)
                else -> GLES20.glUniform3f(uColor, 0.62f, 0.80f, 0.70f)
            }
            GLES20.glUniform1f(uPulseAmount, if (v.home) 0.25f else 1f)

            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, if (isNode) nodeVertexCount else pillarVertexCount)
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aSeed)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawComposite(time: Float) {
        val p = compositeProgram
        if (p == 0) return
        GLES20.glUseProgram(p)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uLayer"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), time)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uPulse"), pulse)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAberration"), BASE_ABERRATION)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uScanlines"), layerH * 1.6f)
        drawQuad(p)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(program: Int) {
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private companion object {
        const val PILLAR_POINTS = 320
        const val PILLAR_HEIGHT_M = 45f
        const val NODE_POINT_M = 0.014f
        const val PILLAR_POINT_M = 0.09f
        const val NODE_SPIN_DEG_PER_S = 9f
        const val HOME_SPIN_DEG_PER_S = 3f
        const val PULSE_DECAY_S = 0.45f
        const val BASE_ABERRATION = 0.0022f
    }
}
