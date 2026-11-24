package com.lobbyism.app.ui.outdoor

import com.lobbyism.app.core.Config
import com.lobbyism.app.data.NodeRepository
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.sensors.LocationTracker
import com.lobbyism.app.ui.Dialogs

/**
 * State holder for the outdoor mode's own actions: leaving a message at the
 * current position. (Discovery and lock run in GameLoop / WorldTracker.)
 */
class OutdoorController(
    private val cfg: Config,
    private val nodes: NodeRepository,
    private val location: LocationTracker,
    private val dialogs: Dialogs,
    /** A node was placed (the tutorial listens). */
    private val onPlaced: (nodeId: String) -> Unit,
) {
    /** The main button outdoors. */
    fun askForText() {
        val fix = location.fix?.takeIf { it.isFresh() }
        if (fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            dialogs.toast("Kein Signal. Ohne Position kann nichts hinterlassen werden.")
            return
        }
        val accuracyNote = if (fix.accuracyM > cfg.placeMaxAccuracyM) {
            "\n\nSignal ungenau (±${fix.accuracyM.toInt()} m). Die Nachricht landet evtl. ein Stück daneben."
        } else {
            ""
        }
        dialogs.textInput(
            title = "Hier hinterlassen",
            message = "Deine Nachricht bleibt an diesem Ort.$accuracyNote",
            hint = "Was bleibt hier?",
            maxChars = cfg.textMaxChars,
            confirm = "Ablegen",
        ) { text -> place(text) }
    }

    private fun place(text: String) {
        // Position at the moment of confirming, not of opening the dialog: the player may have walked on.
        val fix = location.fix?.takeIf { it.isFresh() }
        if (text.isEmpty() || fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            dialogs.toast(if (text.isEmpty()) "Leere Nachricht." else "Signal verloren. Nichts abgelegt.")
            return
        }
        val node = nodes.placeText(text, fix.lat, fix.lon)
        SensoryFeedback.emit(SensoryFeedback.Cue.NODE_PLACED)
        onPlaced(node.id)
    }
}
