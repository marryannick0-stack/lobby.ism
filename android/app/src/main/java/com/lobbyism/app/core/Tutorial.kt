package com.lobbyism.app.core

/**
 * Guided first walk: teaches every mechanic once and calibrates the app on the way.
 *
 * Pure logic, no Android. The activity feeds it a [Snapshot] every frame plus
 * discrete events (node placed, node locked, button pressed). A step only
 * advances when the sensors confirm that it really worked: no "next" button
 * for things the player must actually do.
 *
 * Thread-safety: all public methods are synchronized. The activity calls
 * [update] from the GL thread and the event methods from the main thread.
 */
class Tutorial(private val cfg: TutorialConfig = TutorialConfig()) {

    enum class Step {
        WELCOME,
        CAMERA_CHECK,
        GPS,
        NORTH,
        PLACE,
        LOCK,
        LEAVE,
        FIND,
        RETURN,
        DONE,
    }

    /** Sensor state for one frame. */
    class Snapshot(
        val hasFix: Boolean,
        val accuracyM: Double,
        val speedMps: Double,
        val headingReady: Boolean,
        val headingConfidence: Double,
        /** Stage of the node placed during the tutorial, or null if not visible/known. */
        val tutorialNodeStage: WorldTracker.Stage?,
        val tutorialNodeDistanceM: Double?,
        /** Signed horizontal angle from the view direction to the tutorial node, degrees. Positive = to the right. */
        val tutorialNodeTurnDeg: Double?,
    )

    /** What the overlay shows. Immutable. */
    data class View(
        val step: Step,
        val index: Int,
        val total: Int,
        val title: String,
        val body: String,
        /** Optional live line, e.g. measured accuracy. */
        val live: String?,
        /** 0..1, or null when the step has no measurable progress. */
        val progress: Float?,
        /** Extra hint after the player has been stuck for a while. */
        val hint: String?,
        val primaryButton: String?,
        val secondaryButton: String?,
        /** Turn indicator for the HUD: signed degrees to the tutorial node, or null. */
        val turnDeg: Double?,
        /** true if the "+ HINTERLASSEN" button should be highlighted. */
        val highlightPlace: Boolean,
    )

    var step: Step = Step.WELCOME
        @Synchronized get
        private set

    /** Id of the node the player placed during this tutorial. */
    var tutorialNodeId: String? = null
        @Synchronized get
        private set

    private var stepTimeS = 0.0
    private var maxDistanceM = 0.0
    private var justCompleted = false

    val isActive: Boolean
        @Synchronized get() = step != Step.DONE

    @Synchronized
    fun restart() {
        step = Step.WELCOME
        tutorialNodeId = null
        enter(Step.WELCOME)
    }

    /** The player pressed the primary button. */
    @Synchronized
    fun onPrimary() {
        when (step) {
            Step.WELCOME -> advance()
            Step.CAMERA_CHECK -> advance() // "image is upright"
            Step.DONE -> Unit
            else -> Unit
        }
    }

    /** The player pressed the secondary button. Returns true if the camera should be rotated. */
    @Synchronized
    fun onSecondary(): Boolean = when (step) {
        Step.CAMERA_CHECK -> true // "rotate the image", stay on this step
        else -> false
    }

    @Synchronized
    fun onNodePlaced(nodeId: String) {
        if (step == Step.PLACE) {
            tutorialNodeId = nodeId
            advance()
        }
    }

    @Synchronized
    fun onNodeLocked(nodeId: String) {
        if (nodeId != tutorialNodeId) return
        if (step == Step.LOCK || step == Step.RETURN) advance()
    }

    /** Skip the whole tutorial. */
    @Synchronized
    fun finish() {
        step = Step.DONE
    }

    /**
     * Advances automatic steps and returns what to show.
     * @return the current view; [consumeCompletion] tells whether a step just finished.
     */
    @Synchronized
    fun update(dtS: Double, s: Snapshot): View {
        stepTimeS += dtS
        when (step) {
            Step.GPS -> if (s.hasFix && s.accuracyM <= cfg.gpsGoodAccuracyM) advance()
            Step.NORTH -> if (s.headingReady) advance()
            Step.LEAVE -> {
                s.tutorialNodeDistanceM?.let { maxDistanceM = maxOf(maxDistanceM, it) }
                // Done when the node has left the materialised state and turned into a sky pillar.
                if (s.tutorialNodeStage == WorldTracker.Stage.PILLAR && maxDistanceM >= cfg.leaveMinDistanceM) advance()
            }
            Step.FIND -> {
                val turn = s.tutorialNodeTurnDeg
                if (s.tutorialNodeStage == WorldTracker.Stage.PILLAR && turn != null &&
                    kotlin.math.abs(turn) <= cfg.findAngleDeg
                ) advance()
                // Walked straight back without looking for the pillar: still fine.
                if (s.tutorialNodeStage == WorldTracker.Stage.MATERIALIZED) advance()
            }
            else -> Unit
        }
        return view(s)
    }

    /** true exactly once after a step was completed (for a confirmation cue). */
    @Synchronized
    fun consumeCompletion(): Boolean {
        val r = justCompleted
        justCompleted = false
        return r
    }

    private fun advance() {
        val next = Step.values()[(step.ordinal + 1).coerceAtMost(Step.DONE.ordinal)]
        step = next
        justCompleted = true
        enter(next)
    }

    private fun enter(s: Step) {
        stepTimeS = 0.0
        if (s == Step.LEAVE) maxDistanceM = 0.0
    }

    private fun stuck(afterS: Double) = stepTimeS >= afterS

    private fun view(s: Snapshot): View {
        val total = Step.values().size - 1 // DONE is not counted as a step
        val index = step.ordinal + 1
        fun v(
            title: String,
            body: String,
            live: String? = null,
            progress: Float? = null,
            hint: String? = null,
            primary: String? = null,
            secondary: String? = null,
            turn: Double? = null,
            highlightPlace: Boolean = false,
        ) = View(step, index, total, title, body, live, progress, hint, primary, secondary, turn, highlightPlace)

        return when (step) {
            Step.WELCOME -> v(
                "Erster Gang",
                "Ca. 10 Minuten, draußen, mit freiem Blick zum Himmel. Du brauchst etwa 40 m freien Weg geradeaus.\n\n" +
                    "Halte das Handy die ganze Zeit aufrecht vor dir, wie beim Fotografieren.",
                primary = "LOS",
            )

            Step.CAMERA_CHECK -> v(
                "Kamera prüfen",
                "Schau auf das Bild: Ist der Himmel oben und der Boden unten?",
                primary = "JA, PASST",
                secondary = "BILD DREHEN",
            )

            Step.GPS -> v(
                "Signal suchen",
                "Bleib stehen und warte. Die App sucht Satelliten.",
                live = if (s.hasFix) "Genauigkeit ±${s.accuracyM.toInt()} m (Ziel: ±${cfg.gpsGoodAccuracyM.toInt()} m)" else "Noch kein Signal",
                progress = if (s.hasFix) (cfg.gpsGoodAccuracyM / s.accuracyM).toFloat().coerceIn(0f, 1f) else 0f,
                hint = if (stuck(45.0)) "Dauert zu lang? Weg von Häusern und Bäumen, unter freien Himmel." else null,
            )

            Step.NORTH -> v(
                "Norden finden",
                "Geh jetzt zügig 30 Schritte geradeaus. Handy aufrecht, Kamera zeigt in Laufrichtung.\n\n" +
                    "Die App lernt dabei, wo Norden ist. Ohne Kompass.",
                live = if (s.speedMps < cfg.minWalkSpeedMps) "Zu langsam — normal gehen" else "Gut, weiter geradeaus",
                progress = s.headingConfidence.toFloat().coerceIn(0f, 1f),
                hint = if (stuck(60.0)) "Klappt nicht? Gerade Strecke, gleichmäßiges Tempo, nicht abbiegen." else null,
            )

            Step.PLACE -> v(
                "Etwas hinterlassen",
                "Bleib stehen. Tippe unten auf + HINTERLASSEN, schreib „Test“ und lege es ab.",
                highlightPlace = true,
            )

            Step.LOCK -> v(
                "Lock",
                "Vor dir ist jetzt eine flackernde Punktwolke. Ziel mit dem Fadenkreuz in der Mitte drauf und halte still.\n\n" +
                    "Vier kurze Vibrationen, dann rastet es ein.",
                hint = if (stuck(25.0)) "Nichts zu sehen? Langsam einmal um dich selbst drehen, auch leicht nach unten schauen." else null,
                turn = s.tutorialNodeTurnDeg,
            )

            Step.LEAVE -> v(
                "Weggehen",
                "Tipp die Nachricht weg. Dreh dich um und geh geradeaus weg, bis die Punktwolke verschwindet.",
                live = s.tutorialNodeDistanceM?.let { "Abstand ${it.toInt()} m" },
                progress = s.tutorialNodeDistanceM?.let { (it / cfg.leaveMinDistanceM).toFloat().coerceIn(0f, 1f) },
                hint = if (stuck(90.0)) "Weiter weg gehen. Bei schlechtem Signal braucht es mehr Abstand." else null,
            )

            Step.FIND -> v(
                "Wiederfinden",
                "Dreh dich langsam um. Such die dünne Lichtsäule, die aus dem Boden in den Himmel steigt.\n\n" +
                    "Der Pfeil zeigt dir die Richtung.",
                turn = s.tutorialNodeTurnDeg,
                hint = if (stuck(30.0)) "Handy etwas nach oben neigen: Die Säule ist hoch." else null,
            )

            Step.RETURN -> v(
                "Zurück",
                "Geh auf die Säule zu, bis die Punktwolke wieder erscheint. Dann lock sie noch einmal.",
                live = s.tutorialNodeDistanceM?.let { "Abstand ${it.toInt()} m" },
                turn = s.tutorialNodeTurnDeg,
            )

            Step.DONE -> v(
                "Fertig",
                "Alles kalibriert. Das Tutorial kannst du jederzeit unter ≡ neu starten.",
                primary = "OK",
            )
        }
    }
}

/** Tunables of the tutorial. Separate from [Config] so the tutorial stays self-contained. */
data class TutorialConfig(
    val gpsGoodAccuracyM: Double = 15.0,
    val minWalkSpeedMps: Double = 0.9,
    val leaveMinDistanceM: Double = 15.0,
    val findAngleDeg: Double = 15.0,
)
