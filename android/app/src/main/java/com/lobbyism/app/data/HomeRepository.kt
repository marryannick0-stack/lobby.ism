package com.lobbyism.app.data

import com.lobbyism.app.core.HomePin
import com.lobbyism.app.core.HomeStation
import java.util.UUID

/**
 * Single source of truth for the home base: stations and their pins.
 *
 * Private by design (GDD: home is personal): this repository has no network
 * path at all. Nothing in here can ever reach a server.
 */
class HomeRepository(private val store: NodeStore) {

    fun stations(): List<HomeStation> = store.stations()

    fun createStation(name: String, reference: String): HomeStation {
        val st = HomeStation(UUID.randomUUID().toString(), name, reference, System.currentTimeMillis())
        store.insertStation(st)
        return st
    }

    /** Deletes the station and all of its pins. */
    fun deleteStation(stationId: String) = store.deleteStation(stationId)

    fun pins(stationId: String): List<HomePin> = store.pins(stationId)

    fun addPin(
        stationId: String,
        relAzimuthDeg: Double,
        elevationDeg: Double,
        distanceM: Double,
        text: String,
    ): HomePin {
        val pin = HomePin(
            id = UUID.randomUUID().toString(),
            stationId = stationId,
            relAzimuthDeg = relAzimuthDeg,
            elevationDeg = elevationDeg,
            distanceM = distanceM,
            text = text,
            createdAtMs = System.currentTimeMillis(),
        )
        store.insertPin(pin)
        return pin
    }

    fun deletePin(pinId: String) = store.deletePin(pinId)
}
