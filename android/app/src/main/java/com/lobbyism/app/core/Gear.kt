package com.lobbyism.app.core

/**
 * Gear catalogue (Loot System v2.0, GDD section 03, Art Constraints 1-3).
 *
 * Three slots on three fixed body zones, so a player can read a loadout in
 * under a second: OFFENSE = upper body, DEFENSE = torso, UTILITY = hips/ground.
 * The zone of every item is fixed here and must not move (Art Constraints 1).
 */
enum class GearSlot(val label: String, val zone: String) {
    OFFENSE("OFFENSE", "Kopf / Schultern / Arme"),
    DEFENSE("DEFENSE", "Torso"),
    UTILITY("UTILITY", "Hüfte / Beine / Boden"),
}

/**
 * The eight base items. [form] is the visual rule from Art Constraints 2:
 * items within a slot differ by FORM and position, never by colour.
 */
enum class GearType(val slot: GearSlot, val title: String, val form: String) {
    SCANNER_IMPLANT(GearSlot.OFFENSE, "Scanner Implant", "Kopf, Antennen, empfangend"),
    OVERCHARGE_AMP(GearSlot.OFFENSE, "Overcharge Amp", "Arme und Hände, ausstrahlend"),
    SIGNAL_BOOSTER(GearSlot.OFFENSE, "Signal Booster", "eine Schulter, asymmetrisch"),
    REACTIVE_ARMOR(GearSlot.DEFENSE, "Reactive Armor", "Torso, Platten: mehr Material"),
    GHOST_PROTOCOL(GearSlot.DEFENSE, "Ghost Protocol", "Torso fehlt: weniger Material"),
    NOISE_FILTER(GearSlot.DEFENSE, "Noise Filter", "Torso ruhig: klarere Kontur"),
    TRAP_BEACON(GearSlot.UTILITY, "Trap Beacon", "Ring am Boden, strahlt ab"),
    COURIER_NETWORK(GearSlot.UTILITY, "Courier Network", "Taschen an der Hüfte"),
}

/**
 * Rarity tiers. They differ by STABILITY (glitch), not by colour
 * (Art Constraints 3): T1 is noise, T4 is presence and the only tier with an aura.
 */
enum class GearTier(val number: Int, val label: String) {
    STATIC(1, "STATIC"),
    CLEAR(2, "CLEAR"),
    LOCKED(3, "LOCKED"),
    RESONANT(4, "RESONANT"),
}

/**
 * One owned gear piece in the stash. Mirrors the server model `gear_item`
 * from the loot system, so the prototype stock can be replaced by real loot
 * without changing anything downstream.
 *
 * Rule R4 (server authority): real gear will be created by the server only.
 * Items with [prototype] = true are local test stock and are labelled as such.
 */
data class GearItem(
    val id: String,
    val type: GearType,
    val tier: GearTier,
    /** Gear-Memoria: where and when it was found, written once. Human-readable. */
    val origin: String,
    val prototype: Boolean,
    val createdAtMs: Long,
)

/** Rules of the stash. Pure, so they can be tested. */
object Stash {
    /** Whether one more item fits. */
    fun hasRoom(itemCount: Int, slots: Int): Boolean = itemCount < slots
}
