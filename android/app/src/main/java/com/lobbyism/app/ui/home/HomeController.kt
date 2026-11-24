package com.lobbyism.app.ui.home

import com.lobbyism.app.core.Config
import com.lobbyism.app.core.GearItem
import com.lobbyism.app.core.GearTier
import com.lobbyism.app.core.GearType
import com.lobbyism.app.core.HomeObject
import com.lobbyism.app.core.HomeObjectKind
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
 * Owns: whether home mode is on, the active station, its objects, the
 * alignment, and the object currently being carried. Runs all home dialogs.
 * Draws nothing itself: the activity reads [ui] after [onChanged] and
 * renders buttons from it (unidirectional flow).
 *
 * Placing anything works the same way: choose what, then it hangs at the
 * crosshair ("carrying") until the player taps "HIER ABSTELLEN".
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
    data class Ui(
        val active: Boolean,
        val hasStation: Boolean,
        val aligned: Boolean,
        val carrying: Boolean,
    )

    /** An object following the crosshair. [isNew] = not stored yet. */
    private class Carry(val obj: HomeObject, val isNew: Boolean)

    private val tracker = HomeTracker(cfg)

    @Volatile private var active = false
    @Volatile private var station: HomeStation? = null
    @Volatile private var objects: List<HomeObject> = emptyList()
    @Volatile private var carry: Carry? = null

    val isActive: Boolean get() = active

    val ui: Ui get() = Ui(active, station != null, tracker.isAligned, carry != null)

    // ------------------------------------------------------------ GL thread

    fun frame(dtS: Float, forward: FloatArray): WorldTracker.Frame = tracker.update(dtS, forward, objects, carry?.obj)

    fun statusLines(visible: Int): List<String> {
        val st = station ?: return listOf("HOME", "STATION WÄHLEN")
        val name = st.name.uppercase()
        val c = carry
        return when {
            !tracker.isAligned -> listOf("HOME · $name", "ZIEL AUF: ${st.reference.uppercase()}", "DANN UNTEN AUSRICHTEN TIPPEN")
            c != null && c.obj.kind == HomeObjectKind.GEAR -> listOf(
                "TRAGEN · ${c.obj.gear?.type?.title?.uppercase() ?: "RÜSTUNG"}",
                "AUF DEN BODEN ZIELEN, WO SIE STEHEN SOLL",
                "DANN HIER ABSTELLEN · ↶ ABBRECHEN",
            )
            c != null -> listOf("TRAGEN · NOTIZ", "ZIELEN, WO SIE HÄNGEN SOLL", "DANN HIER ABSTELLEN · ↶ ABBRECHEN")
            else -> listOf(
                "HOME · $name · AUSGERICHTET",
                "OBJEKTE $visible · VOM STANDPUNKT AUS SCHAUEN",
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
            carry != null -> setDown()
            else -> placeMenu()
        }
    }

    /** The small button above the home button: cancel carrying, otherwise re-align. */
    fun onSecondaryAction() {
        if (carry != null) {
            carry = null
            onChanged()
        } else {
            align()
        }
    }

    /** Long press on the home button. */
    fun manage() {
        val st = station ?: return
        dialogs.choose(st.name, listOf("Station wechseln", "Station löschen")) { which ->
            when (which) {
                0 -> pickStation()
                1 -> dialogs.confirm(
                    "„${st.name}“ löschen?",
                    "Alle ${objects.size} Objekte dieser Station werden entfernt. Rüstungen gehen zurück in den Stash.",
                    "Löschen",
                ) {
                    repo.deleteStation(st.id)
                    leave()
                }
            }
        }
    }

    /** Text for the content card of a home armour stand, or null for notes (they show their text). */
    fun describe(objectId: String): String? {
        val o = objects.firstOrNull { it.id == objectId } ?: return null
        val g = o.gear ?: return null
        val proto = if (g.prototype) "\n[ PROTOTYP — kein echtes Loot ]" else ""
        return "${g.type.slot.label} // ${g.type.title.uppercase()}\n" +
            "T${g.tier.number} ${g.tier.label}\n\n" +
            "Zone: ${g.type.slot.zone}\nForm: ${g.type.form}\n\n" +
            "Memoria: ${g.origin}$proto"
    }

    /** The content card of a home object was tapped. [onDone] closes the card. */
    fun onObjectTapped(objectId: String, onDone: () -> Unit) {
        val o = objects.firstOrNull { it.id == objectId } ?: return onDone()
        val isGear = o.kind == HomeObjectKind.GEAR
        val remove = if (isGear) "Zurück in den Stash" else "Notiz löschen"
        dialogs.choose(if (isGear) "Rüstung" else "Notiz", listOf("Schließen", "Verschieben", remove), cancelable = false) { which ->
            when (which) {
                1 -> {
                    carry = Carry(o, isNew = false)
                    onChanged()
                }
                2 -> {
                    repo.removeObject(o.id)
                    reload()
                    SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_LOST)
                }
            }
            onDone()
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

    // ------------------------------------------------------------- placing

    private fun placeMenu() {
        val stash = repo.stash()
        dialogs.choose(
            "Platzieren",
            listOf(
                "Notiz",
                "Rüstung aus dem Stash (${stash.size}/${repo.stashSlots})",
                "Prototyp-Rüstung erzeugen",
                "Stash verwalten",
            ),
        ) { which ->
            when (which) {
                0 -> askForNote()
                1 -> pickFromStash()
                2 -> createPrototype()
                3 -> manageStash()
            }
        }
    }

    private fun askForNote() {
        val st = station ?: return
        dialogs.textInput(
            title = "Notiz",
            message = "Danach hängt sie am Fadenkreuz, bis du sie abstellst.",
            hint = "Was soll hier hängen?",
            maxChars = cfg.textMaxChars,
            confirm = "Weiter",
        ) { text ->
            if (text.isEmpty()) {
                SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            } else {
                startCarrying(repo.draftNote(st.id, text))
            }
        }
    }

    private fun pickFromStash() {
        val st = station ?: return
        val stash = repo.stash()
        if (stash.isEmpty()) {
            dialogs.toast("Stash ist leer. Erzeuge zuerst eine Prototyp-Rüstung.")
            return
        }
        val placed = repo.placedGear()
        dialogs.choose("Stash", stash.map { label(it, placed[it.id]) }) { which ->
            val item = stash[which]
            val where = placed[item.id]
            if (where != null) {
                dialogs.toast("Steht schon in „$where“. Dort verschieben oder zurücklegen.")
            } else {
                startCarrying(repo.draftStand(st.id, item))
            }
        }
    }

    private fun createPrototype() {
        val st = station ?: return
        if (!repo.stashHasRoom()) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            dialogs.toast("Stash voll (${repo.stashSlots} Plätze).")
            return
        }
        val types = GearType.values()
        dialogs.choose("Welches Teil?", types.map { "${it.slot.label} · ${it.title}" }) { t ->
            val tiers = GearTier.values()
            dialogs.choose("Welcher Tier?", tiers.map { "T${it.number} ${it.label}" }) { r ->
                val item = repo.createPrototype(types[t], tiers[r]) ?: return@choose
                startCarrying(repo.draftStand(st.id, item))
            }
        }
    }

    private fun manageStash() {
        val stash = repo.stash()
        if (stash.isEmpty()) {
            dialogs.toast("Stash ist leer.")
            return
        }
        val placed = repo.placedGear()
        dialogs.choose("Stash verwalten: löschen", stash.map { label(it, placed[it.id]) }) { which ->
            val item = stash[which]
            dialogs.confirm(
                "${item.type.title} löschen?",
                "Das Teil verschwindet endgültig, auch von seinem Ständer.",
                "Löschen",
            ) {
                repo.deleteGear(item.id)
                reload()
            }
        }
    }

    private fun startCarrying(obj: HomeObject) {
        carry = Carry(obj, isNew = true)
        onChanged()
    }

    /** Stores the carried object where the crosshair points now. */
    private fun setDown() {
        val c = carry ?: return
        val placement = pose.read()?.let { tracker.placementFromAim(c.obj.kind, it.first) }
        if (placement == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            return
        }
        val placed = repo.setDown(c.obj, placement, c.isNew)
        tracker.suppressLock(placed)
        carry = null
        reload()
        SensoryFeedback.emit(SensoryFeedback.Cue.NODE_PLACED)
    }

    // ------------------------------------------------------------- stations

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
            info = "Stell dich an die Stelle, von der aus du die Objekte sehen willst.\n\n" +
                "Der Bezugspunkt muss sich nie bewegen und von hier gut sichtbar sein: " +
                "Lichtschalter, Türrahmen-Ecke, Steckdose. Auf ihn zielst du bei jedem Betreten.",
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
        carry = null
        station = st
        objects = repo.objects(st.id)
        active = true
        onChanged()
    }

    private fun leave() {
        active = false
        station = null
        objects = emptyList()
        carry = null
        tracker.leaveStation()
        onChanged()
    }

    private fun reload() {
        val st = station
        objects = if (st == null) emptyList() else repo.objects(st.id)
        onChanged()
    }

    private fun label(g: GearItem, placedIn: String?): String {
        val where = if (placedIn != null) " · steht in $placedIn" else ""
        val proto = if (g.prototype) " · PROTOTYP" else ""
        return "${g.type.title} · T${g.tier.number} ${g.tier.label}$where$proto"
    }

    private companion object {
        const val STATION_NAME_MAX = 40
        const val STATION_REFERENCE_MAX = 60
    }
}
