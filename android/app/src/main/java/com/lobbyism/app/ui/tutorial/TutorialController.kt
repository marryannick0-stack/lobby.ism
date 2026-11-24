package com.lobbyism.app.ui.tutorial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lobbyism.app.core.Geo
import com.lobbyism.app.core.Node
import com.lobbyism.app.core.Tutorial
import com.lobbyism.app.core.TutorialConfig
import com.lobbyism.app.core.WorldTracker
import com.lobbyism.app.data.Settings
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.sensors.LocationTracker

/**
 * State holder of the guided first walk (logic lives in core/Tutorial.kt).
 *
 * Feeds the tutorial with each frame's measurements, posts changed cards to
 * the overlay, and remembers in [Settings] when the walk is done.
 *
 * Threads: [frame], [onNodePlaced] and [onNodeLocked] may run on any thread
 * (Tutorial is synchronized); everything touching views runs on the main thread.
 */
class TutorialController(
    context: Context,
    cfg: TutorialConfig,
    private val settings: Settings,
    /** Main thread: the tutorial asks to rotate the camera image by 90 degrees. */
    private val onRotateCamera: () -> Unit,
    /** Main thread: visibility or the place-button highlight changed. */
    private val onChanged: () -> Unit,
) {
    private val tutorial = Tutorial(cfg)
    private val main = Handler(Looper.getMainLooper())

    val overlay = TutorialOverlay(
        context,
        onPrimary = { if (tutorial.step == Tutorial.Step.DONE) close() else tutorial.onPrimary() },
        onSecondary = { if (tutorial.onSecondary()) onRotateCamera() },
        onSkip = { close() },
    ).apply { visibility = View.GONE }

    /** true while the card is on screen, including the final "done" card. */
    @Volatile var isRunning = !settings.tutorialDone
        private set

    /** Hidden while another mode (home base) has the screen; the walk is paused, not ended. */
    @Volatile private var suspended = false

    /** Last card handed to the main thread, so unchanged frames post nothing. */
    @Volatile private var lastCard: Tutorial.View? = null

    /** Main thread: whether the "+ HINTERLASSEN" button should be highlighted. */
    var highlightPlace = false
        private set

    // ------------------------------------------------------------ GL thread

    /**
     * Advances the walk with this frame's measurements.
     * @return degrees the player should turn towards the tutorial node, or null
     */
    fun frame(
        dtS: Float,
        fix: LocationTracker.Fix?,
        headingReady: Boolean,
        headingConfidence: Float,
        frame: WorldTracker.Frame?,
        nodes: List<Node>,
        forward: FloatArray,
        top: FloatArray,
    ): Double? {
        if (!isRunning || suspended) return null

        val nodeId = tutorial.tutorialNodeId
        val nodeView = frame?.views?.firstOrNull { it.node.id == nodeId }
        val node = nodeId?.let { id -> nodes.firstOrNull { it.id == id } }
        val distance = if (node != null && fix != null) Geo.distanceM(fix.lat, fix.lon, node.lat, node.lon) else null
        val turn = nodeView?.let {
            val facing = WorldTracker.sensorAzimuthDeg(WorldTracker.horizontalForward(forward, top))
            Geo.wrap180(WorldTracker.sensorAzimuthDeg(it.pos) - facing)
        }

        val view = tutorial.update(
            dtS.toDouble(),
            Tutorial.Snapshot(
                hasFix = fix != null,
                accuracyM = fix?.accuracyM ?: 99.0,
                speedMps = fix?.speedMps ?: 0.0,
                headingReady = headingReady,
                headingConfidence = headingConfidence.toDouble(),
                tutorialNodeStage = nodeView?.stage,
                tutorialNodeDistanceM = distance,
                tutorialNodeTurnDeg = turn,
            ),
        )
        if (tutorial.consumeCompletion()) SensoryFeedback.emit(SensoryFeedback.Cue.STEP_DONE)

        // The turn angle changes every frame but is drawn by the HUD, not the card:
        // compare without it so the main thread only gets real changes.
        val card = view.copy(turnDeg = null)
        if (card != lastCard) {
            lastCard = card
            main.post { show(card) }
        }
        return view.turnDeg
    }

    fun onNodePlaced(nodeId: String) {
        if (isRunning) tutorial.onNodePlaced(nodeId)
    }

    fun onNodeLocked(nodeId: String) {
        if (isRunning) tutorial.onNodeLocked(nodeId)
    }

    // ----------------------------------------------------------- main thread

    fun start() {
        tutorial.restart()
        lastCard = null
        isRunning = true
        suspended = false
        overlay.visibility = View.VISIBLE
        onChanged()
    }

    /** Pause (true) or resume (false) while another mode owns the screen. */
    fun setSuspended(value: Boolean) {
        suspended = value
        lastCard = null // forces the card to be redrawn on resume
        overlay.visibility = if (isRunning && !value) View.VISIBLE else View.GONE
        if (value) highlightPlace = false
        onChanged()
    }

    private fun close() {
        tutorial.finish()
        isRunning = false
        settings.tutorialDone = true
        lastCard = null
        overlay.visibility = View.GONE
        highlightPlace = false
        onChanged()
    }

    private fun show(card: Tutorial.View) {
        if (!isRunning || suspended) return
        overlay.visibility = View.VISIBLE
        overlay.show(card)
        if (card.highlightPlace != highlightPlace) {
            highlightPlace = card.highlightPlace
            onChanged()
        }
    }
}
