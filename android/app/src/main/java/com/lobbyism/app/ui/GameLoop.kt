package com.lobbyism.app.ui

import android.os.Handler
import android.os.Looper
import com.lobbyism.app.core.Config
import com.lobbyism.app.core.HeadingEstimator
import com.lobbyism.app.core.Node
import com.lobbyism.app.core.WorldTracker
import com.lobbyism.app.data.NodeRepository
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.render.ArRenderer
import com.lobbyism.app.sensors.LocationTracker
import com.lobbyism.app.sensors.OrientationTracker
import com.lobbyism.app.ui.home.HomeController
import com.lobbyism.app.ui.tutorial.TutorialController

/**
 * One tick of the game, once per rendered frame, on the GL thread.
 *
 *   sensors ─▶ tracker (outdoor world or home base) ─▶ views for the renderer
 *                         └▶ events ─▶ SensoryFeedback (haptics, sound, glitch)
 *                                  └▶ content card, tutorial
 *                         └▶ HUD state
 *
 * Holds no UI views except the HUD it writes into. Everything it hands to the
 * main thread goes through [main].
 */
class GameLoop(
    cfg: Config,
    private val orientation: OrientationTracker,
    private val location: LocationTracker,
    private val nodes: NodeRepository,
    private val home: HomeController,
    private val tutorial: TutorialController,
    private val pose: PoseCache,
    private val hud: HudView,
    /** Main thread: a lock completed on this node. */
    private val onLocked: (Node) -> Unit,
) : ArRenderer.Scene {

    private val world = WorldTracker(cfg)
    private val heading = HeadingEstimator(
        cfg.headingMinSpeedMps, cfg.headingMaxCourseAccuracyDeg, cfg.headingSamplesNeeded, cfg.headingSmoothing,
    )
    private val main = Handler(Looper.getMainLooper())

    // Written on the main thread (GPS callback), read on the GL thread.
    @Volatile private var headingOffsetDeg: Double? = null
    @Volatile private var headingConfidence = 0f
    // Written on the GL thread, read on the main thread.
    @Volatile private var lastSensorAzimuthDeg: Double? = null

    /** Main thread: every GPS fix may teach the heading estimator where north is (rule R2: no compass). */
    fun onGpsFix(fix: LocationTracker.Fix) {
        val course = fix.courseDeg ?: return
        val azimuth = lastSensorAzimuthDeg ?: return
        heading.addSample(course, azimuth, fix.speedMps, fix.courseAccuracyDeg)
        headingOffsetDeg = if (heading.isReady) heading.offsetDeg else null
        headingConfidence = heading.confidence.toFloat()
    }

    override fun frame(dtS: Float, poseValid: Boolean, forward: FloatArray, top: FloatArray): List<WorldTracker.NodeView> {
        val fix = location.fix?.takeIf { it.isFresh() }
        val inHome = home.isActive
        val allNodes = nodes.nodes

        if (poseValid) {
            lastSensorAzimuthDeg = WorldTracker.sensorAzimuthDeg(WorldTracker.horizontalForward(forward, top))
            pose.write(forward, top)
        }

        val frame = when {
            !poseValid -> null
            inHome -> home.frame(dtS, forward)
            else -> world.update(
                dtS, forward, top, fix?.lat, fix?.lon, fix?.accuracyM ?: NO_FIX_ACCURACY_M, headingOffsetDeg, allNodes,
            )
        }

        frame?.events?.forEach { e ->
            when (e) {
                is WorldTracker.Event.Materialized -> SensoryFeedback.emit(SensoryFeedback.Cue.NODE_MATERIALIZED)
                is WorldTracker.Event.LockTick -> SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_TICK, e.progress)
                is WorldTracker.Event.Locked -> {
                    SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_COMPLETE)
                    tutorial.onNodeLocked(e.node.id)
                    main.post { onLocked(e.node) }
                }
                is WorldTracker.Event.LockLost -> SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_LOST, 0.5f)
            }
        }

        val turnDeg = if (inHome) {
            null
        } else {
            tutorial.frame(dtS, fix, headingOffsetDeg != null, headingConfidence, frame, allNodes, forward, top)
        }

        hud.state = HudView.State(
            gpsEnabled = location.isGpsEnabled,
            hasFix = fix != null,
            accuracyM = fix?.accuracyM ?: 0.0,
            headingReady = headingOffsetDeg != null,
            headingConfidence = headingConfidence,
            nodesInRange = (frame?.views?.size ?: 0) + (frame?.hiddenUntilHeading ?: 0),
            nearestM = frame?.nearestDistanceM,
            hiddenUntilHeading = frame?.hiddenUntilHeading ?: 0,
            sync = nodes.syncStatus,
            unsynced = nodes.unsyncedCount,
            focusLock = frame?.focus?.lock,
            focusRevealed = frame?.focus?.revealed ?: false,
            sensorOk = orientation.isAvailable,
            turnDeg = turnDeg,
            homeLines = if (inHome) home.statusLines(frame?.views?.size ?: 0) else null,
        )
        return frame?.views ?: emptyList()
    }

    private companion object {
        /** Accuracy assumed without a fix; only widens radii, never blocks anything (rule R1). */
        const val NO_FIX_ACCURACY_M = 99.0
    }
}
