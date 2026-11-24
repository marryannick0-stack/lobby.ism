package com.lobbyism.app.data

import com.lobbyism.app.core.Config
import com.lobbyism.app.core.GearItem
import com.lobbyism.app.core.GearTier
import com.lobbyism.app.core.GearType
import com.lobbyism.app.core.HomeObject
import com.lobbyism.app.core.HomeObjectKind
import com.lobbyism.app.core.HomeStation
import com.lobbyism.app.core.Placement
import com.lobbyism.app.core.Stash
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Single source of truth for the home base: stations, the objects in them,
 * and the stash the gear comes from.
 *
 * Private by design (GDD: home is personal): this repository has no network
 * path at all. Nothing in here can ever reach a server.
 */
class HomeRepository(private val cfg: Config, private val store: NodeStore) {

    // --------------------------------------------------------------- stations

    fun stations(): List<HomeStation> = store.stations()

    fun createStation(name: String, reference: String): HomeStation {
        val st = HomeStation(UUID.randomUUID().toString(), name, reference, System.currentTimeMillis())
        store.insertStation(st)
        return st
    }

    /** Deletes the station and all objects in it. Gear goes back to the stash. */
    fun deleteStation(stationId: String) = store.deleteStation(stationId)

    // ---------------------------------------------------------------- objects

    fun objects(stationId: String): List<HomeObject> = store.objects(stationId)

    /** A new object that is not stored yet; it is carried until the player sets it down. */
    fun draftNote(stationId: String, text: String) = HomeObject(
        UUID.randomUUID().toString(), stationId, HomeObjectKind.NOTE, Placement(0.0, 0.0, 0.0),
        text.take(cfg.textMaxChars), null, System.currentTimeMillis(),
    )

    fun draftStand(stationId: String, gear: GearItem) = HomeObject(
        UUID.randomUUID().toString(), stationId, HomeObjectKind.GEAR, Placement(0.0, 0.0, 0.0),
        "", gear, System.currentTimeMillis(),
    )

    /** Stores a carried object at its final place: insert for a draft, update for a moved object. */
    fun setDown(obj: HomeObject, placement: Placement, isNew: Boolean): HomeObject {
        val placed = obj.copy(placement = placement)
        if (isNew) store.insertObject(placed) else store.updatePlacement(obj.id, placement)
        return placed
    }

    /** Removes an object from the room. A stand's gear stays in the stash. */
    fun removeObject(objectId: String) = store.deleteObject(objectId)

    // ------------------------------------------------------------------ stash

    fun stash(): List<GearItem> = store.stash()

    /** Stash item id → name of the station where it stands. */
    fun placedGear(): Map<String, String> = store.placedGear()

    fun stashHasRoom(): Boolean = Stash.hasRoom(store.stash().size, cfg.stashSlots)

    val stashSlots: Int get() = cfg.stashSlots

    /**
     * Creates a prototype item for testing. Clearly marked: real gear will come
     * only from the server (rule R4) once loot exists.
     * @return the item, or null if the stash is full
     */
    fun createPrototype(type: GearType, tier: GearTier): GearItem? {
        if (!stashHasRoom()) return null
        val now = System.currentTimeMillis()
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMANY).format(Date(now))
        val item = GearItem(
            id = UUID.randomUUID().toString(),
            type = type,
            tier = tier,
            origin = "Prototyp-Bestand, erzeugt am $date",
            prototype = true,
            createdAtMs = now,
        )
        store.insertGear(item)
        return item
    }

    /** Deletes a stash item for good, and takes it off its stand. */
    fun deleteGear(gearId: String) = store.deleteGear(gearId)
}
