package com.lobbyism.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.Geo
import com.lobbyism.app.core.HomePin
import com.lobbyism.app.core.HomeStation
import com.lobbyism.app.core.Node

/**
 * Local copy of all known nodes (SQLite on the phone).
 *
 * The app is offline-first: a node is written here the moment it is placed and
 * uploaded later. That is why a placed message survives closing the app even
 * without any server.
 *
 * All methods are synchronized; they are called from the UI thread and from
 * the sync thread.
 */
class NodeStore(context: Context) : SQLiteOpenHelper(context, "lobbyism.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE nodes (
                id TEXT PRIMARY KEY,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                content_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                owner_id TEXT,
                status TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                synced INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        createHomeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Each step migrates one version further, so any old install catches up.
        if (oldVersion < 2) createHomeTables(db)
    }

    /** Version 2: home base. Private data: these tables are never synced. */
    private fun createHomeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS home_station (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                reference TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS home_pin (
                id TEXT PRIMARY KEY,
                station_id TEXT NOT NULL,
                rel_azimuth_deg REAL NOT NULL,
                elevation_deg REAL NOT NULL,
                distance_m REAL NOT NULL,
                text TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS home_pin_station_idx ON home_pin (station_id)")
    }

    // ------------------------------------------------------------- home base

    @Synchronized
    fun stations(): List<HomeStation> {
        val out = ArrayList<HomeStation>()
        readableDatabase.query("home_station", null, null, null, null, null, "created_at_ms ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    HomeStation(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        reference = c.getString(c.getColumnIndexOrThrow("reference")),
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun insertStation(st: HomeStation) {
        val v = ContentValues().apply {
            put("id", st.id)
            put("name", st.name)
            put("reference", st.reference)
            put("created_at_ms", st.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("home_station", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes a station together with all its pins. */
    @Synchronized
    fun deleteStation(stationId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("home_pin", "station_id = ?", arrayOf(stationId))
            db.delete("home_station", "id = ?", arrayOf(stationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun pins(stationId: String): List<HomePin> {
        val out = ArrayList<HomePin>()
        readableDatabase.query("home_pin", null, "station_id = ?", arrayOf(stationId), null, null, "created_at_ms ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    HomePin(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        stationId = c.getString(c.getColumnIndexOrThrow("station_id")),
                        relAzimuthDeg = c.getDouble(c.getColumnIndexOrThrow("rel_azimuth_deg")),
                        elevationDeg = c.getDouble(c.getColumnIndexOrThrow("elevation_deg")),
                        distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                        text = c.getString(c.getColumnIndexOrThrow("text")),
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun insertPin(p: HomePin) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("station_id", p.stationId)
            put("rel_azimuth_deg", p.relAzimuthDeg)
            put("elevation_deg", p.elevationDeg)
            put("distance_m", p.distanceM)
            put("text", p.text)
            put("created_at_ms", p.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("home_pin", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun deletePin(pinId: String) {
        writableDatabase.delete("home_pin", "id = ?", arrayOf(pinId))
    }

    @Synchronized
    fun all(): List<Node> = query(null, null)

    @Synchronized
    fun unsynced(): List<Node> = query("synced = 0", null)

    @Synchronized
    fun insertLocal(node: Node) {
        writableDatabase.insertWithOnConflict("nodes", null, values(node), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Marks a locally created node as stored on the server. */
    @Synchronized
    fun markSynced(id: String, ownerId: String?) {
        val v = ContentValues().apply {
            put("synced", 1)
            if (ownerId != null) put("owner_id", ownerId)
        }
        writableDatabase.update("nodes", v, "id = ?", arrayOf(id))
    }

    /**
     * Applies the server's answer for one area: server nodes are inserted or
     * updated, and synced local nodes inside the area that the server no longer
     * lists are removed (this is how a moderation removal reaches the phone).
     * Nodes not yet uploaded are never touched.
     */
    @Synchronized
    fun applyServerSnapshot(centerLat: Double, centerLon: Double, radiusM: Double, serverNodes: List<Node>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val serverIds = HashSet<String>()
            for (n in serverNodes) {
                serverIds.add(n.id)
                db.insertWithOnConflict("nodes", null, values(n.copy(synced = true)), SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (local in query("synced = 1", null)) {
                if (local.id in serverIds) continue
                if (Geo.distanceM(centerLat, centerLon, local.lat, local.lon) <= radiusM) {
                    db.delete("nodes", "id = ?", arrayOf(local.id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun query(where: String?, args: Array<String>?): List<Node> {
        val out = ArrayList<Node>()
        readableDatabase.query("nodes", null, where, args, null, null, "created_at_ms DESC").use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }

    private fun read(c: Cursor): Node {
        val ownerIdx = c.getColumnIndexOrThrow("owner_id")
        return Node(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
            lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
            contentType = enumOr(c.getString(c.getColumnIndexOrThrow("content_type")), ContentType.TEXT),
            payload = c.getString(c.getColumnIndexOrThrow("payload")),
            ownerId = if (c.isNull(ownerIdx)) null else c.getString(ownerIdx),
            status = enumOr(c.getString(c.getColumnIndexOrThrow("status")), ContentStatus.LIVE),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
            synced = c.getInt(c.getColumnIndexOrThrow("synced")) != 0,
        )
    }

    private fun values(n: Node) = ContentValues().apply {
        put("id", n.id)
        put("lat", n.lat)
        put("lon", n.lon)
        put("content_type", n.contentType.name)
        put("payload", n.payload)
        put("owner_id", n.ownerId)
        put("status", n.status.name)
        put("created_at_ms", n.createdAtMs)
        put("synced", if (n.synced) 1 else 0)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        try { enumValueOf<T>(name) } catch (e: IllegalArgumentException) { fallback }
}
