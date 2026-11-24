package com.lobbyism.app.ui.home

import com.lobbyism.app.core.Config
import com.lobbyism.app.core.HomePin
import com.lobbyism.app.core.HomeStation
import com.lobbyism.app.core.HomeTracker
import com.lobbyism.app.core.WorldTracker
import com.lobbyism.app.data.HomeRepository
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.ui.Dialogs
import com.lobbyism.app.ui.PoseCache

/**
 * State holder of the home base (see core/HomeBase.kt for the idea).
 *
 * Owns: whether home mode is on, which station is active, its pins, the
 * alignment. Runs all home dialogs. Draws nothing itself: the activity reads
 * [ui] after [onChanged] and renders buttons from it (unidirectional flow).
 *
 * Threads: [frame] and [statusLines] run on the GL thread and only read
 * volatile snapshots; everything else runs on the main thread.
 */
class HomeController(
    private val cfg: Config,
    private val repo: HomeRepository,
    private val pose: PoseCache,
    private val dialogs: Dialogs,
    /** Main thread: home state changed; re-render whatever depends on [ui]. */
    private val onChanged: () -> Unit,
) {
    /** What the buttons need to know. Immutable. */
    data class Ui(val active: Boolean, val hasStation: Boolean, val aligned: Boolean)

    private val tracker = HomeTracker(cfg)

    @Volatile private var active = false
    @Volatile private var station: HomeStation? = null
    @Volatile private var pins: List<HomePin> = emptyList()

    val isActive: Boolean get() = active

    val ui: Ui get() = Ui(active, station != null, tracker.isAligned)

    // ------------------------------------------------------------ GL thread

    fun frame(dtS: Float, forward: FloatArray): WorldTracker.Frame = tracker.update(dtS, forward, pins)

    fun statusLines(visiblePins: Int): List<String> {
        val st = station ?: return listOf("HOME", "STATION WÄHLEN")
        val name = st.name.uppercase()
        return if (!tracker.isAligned) {
            listOf("HOME · $name", "ZIEL AUF: ${st.reference.uppercase()}", "DANN UNTEN AUSRICHTEN TIPPEN")
        } else {
            listOf(
                "HOME · $name · AUSGERICHTET",
                "PINS $visiblePins · VOM STANDPUNKT AUS SCHAUEN",
                "VERSCHOBEN? ⟲ UND NEU AUF ${st.reference.uppercase()}",
            )
        }
    }

    // ----------------------------------------------------------- main thread

    /** The home button: enter (choose a station) or leave. */
    fun toggle() {
        if (active) leave() else pickStation()
    }

    /** The main action button while home mode is on. */
    fun onMainAction() {
        when {
            station == null -> pickStation()
            !tracker.isAligned -> align()
            else -> askForPin()
        }
    }

    /** The player aims at the reference mark right now: fix the station's frame. */
    fun align() {
        if (station == null) return
        val p = pose.read() ?: return
        tracker.align(p.first, p.second)
        SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_COMPLETE)
        onChanged()
    }

    /** Long press on the home button. */
    fun manage() {
        val st = station ?: return
        dialogs.choose(st.name, listOf("Station wechseln", "Station mit allen Pins löschen")) { which ->
            when (which) {
                0 -> pickStation()
                1 -> dialogs.confirm(
                    "„${st.name}“ löschen?",
                    "Alle ${pins.size} Pins dieser Station werden endgültig gelöscht.",
                    "Löschen",
                ) {
                    repo.deleteStation(st.id)
                    leave()
                }
            }
        }
    }

    /** The content card of a home pin was tapped. [onDone] closes the card. */
    fun onPinTapped(pinId: String, onDone: () -> Unit) {
        dialogs.choose("Pin", listOf("Schließen", "Pin löschen"), cancelable = false) { which ->
            if (which == 1) {
                repo.deletePin(pinId)
                reloadPins()
                SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_LOST)
            }
            onDone()
        }
    }

    private fun pickStation() {
        val stations = repo.stations()
        val labels = stations.map { "${it.name}  ·  Bezug: ${it.reference}" } + "+ Neue Station anlegen"
        dialogs.choose("Home-Base: Wo stehst du?", labels) { which ->
            if (which < stations.size) enter(stations[which]) else createStation()
        }
    }

    private fun createStation() {
        dialogs.fields(
            title = "Neue Station",
            info = "Stell dich an die Stelle, von der aus du die Pins sehen willst.\n\n" +
                "Der Bezugspunkt muss sich nie bewegen und von hier gut sichtbar sein: " +
                "Lichtschalter, T\u00fcrrahmen-Ecke, Steckdose. Auf ihn zielst du bei jedem Betreten.",
            fields = listOf(
                Dialogs.Field("Name, z. B. Couch", STATION_NAME_MAX),
                Dialogs.Field("Fester Bezugspunkt, z. B. Lichtschalter", STATION_REFERENCE_MAX),
            ),
            confirm = "Anlegen",
        ) { values ->
            val (name, reference) = values
            if (name.isEmpty() || reference.isEmpty()) {
                SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
                dialogs.toast("Name und Bezugspunkt angeben.")
            } else {
                enter(repo.createStation(name, reference))
            }
        }
    }

    private fun enter(st: HomeStation) {
        tracker.leaveStation()
        station = st
        pins = repo.pins(st.id)
        active = true
        onChanged()
    }

    private fun leave() {
        active = false
        station = null
        pins = emptyList()
        tracker.leaveStation()
        onChanged()
    }

    private fun askForPin() {
        val st = station ?: return
        // Capture the aim NOW: while typing, the phone is lowered and points elsewhere.
        val angles = pose.read()?.let { tracker.aimToPinAngles(it.first) }
        if (angles == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            return
        }
        dialogs.textInput(
            title = "Anpinnen",
            message = "Der Pin hängt dort, wohin das Fadenkreuz eben gezeigt hat.",
            hint = "Was soll hier hängen?",
            maxChars = cfg.textMaxChars,
            confirm = "Anpinnen",
        ) { text ->
            if (text.isEmpty()) {
                SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
                return@textInput
            }
            val pin = repo.addPin(st.id, angles[0], angles[1], cfg.homePinDistanceM, text)
            tracker.suppressLock(pin)
            reloadPins()
            SensoryFeedback.emit(SensoryFeedback.Cue.NODE_PLACED)
        }
    }

    private fun reloadPins() {
        val st = station
        pins = if (st == null) emptyList() else repo.pins(st.id)
        onChanged()
    }

    private companion object {
        const val STATION_NAME_MAX = 40
        const val STATION_REFERENCE_MAX = 60
    }
}
