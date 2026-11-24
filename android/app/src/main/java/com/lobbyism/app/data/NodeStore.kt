package com.lobbyism.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.Geo
import com.lobbyism.app.core.GearItem
import com.lobbyism.app.core.GearTier
import com.lobbyism.app.core.GearType
import com.lobbyism.app.core.HomeObject
import com.lobbyism.app.core.HomeObjectKind
import com.lobbyism.app.core.HomeStation
import com.lobbyism.app.core.Placement
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
class NodeStore(context: Context) : SQLiteOpenHelper(context, "lobbyism.db", null, 3) {

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
        migrateToGear(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Each step migrates one version further, so any old install catches up.
        if (oldVersion < 2) createHomeTables(db)
        if (oldVersion < 3) migrateToGear(db)
    }

    /**
     * Version 3: home objects get a kind (NOTE or GEAR), and the stash exists.
     * Existing pins become NOTE objects. The table keeps its old name home_pin.
     * gear_item mirrors the loot system's server model so real loot can replace
     * the prototype stock later.
     */
    private fun migrateToGear(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE home_pin ADD COLUMN kind TEXT NOT NULL DEFAULT 'NOTE'")
        db.execSQL("ALTER TABLE home_pin ADD COLUMN gear_id TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gear_item (
                id TEXT PRIMARY KEY,
                base_type TEXT NOT NULL,
                tier TEXT NOT NULL,
                origin TEXT NOT NULL,
                prototype INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
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

    /** All objects of a station, with their stash item attached for GEAR objects. */
    @Synchronized
    fun objects(stationId: String): List<HomeObject> {
        val gear = stash().associateBy { it.id }
        val out = ArrayList<HomeObject>()
        readableDatabase.query("home_pin", null, "station_id = ?", arrayOf(stationId), null, null, "created_at_ms ASC").use { c ->
            while (c.moveToNext()) {
                val kind = enumOr(c.getString(c.getColumnIndexOrThrow("kind")), HomeObjectKind.NOTE)
                val gearIdx = c.getColumnIndexOrThrow("gear_id")
                val item = if (c.isNull(gearIdx)) null else gear[c.getString(gearIdx)]
                // A GEAR object whose stash item is gone is skipped instead of shown empty.
                if (kind == HomeObjectKind.GEAR && item == null) continue
                out.add(
                    HomeObject(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        stationId = c.getString(c.getColumnIndexOrThrow("station_id")),
                        kind = kind,
                        placement = Placement(
                            relAzimuthDeg = c.getDouble(c.getColumnIndexOrThrow("rel_azimuth_deg")),
                            elevationDeg = c.getDouble(c.getColumnIndexOrThrow("elevation_deg")),
                            distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                        ),
                        text = c.getString(c.getColumnIndexOrThrow("text")),
                        gear = item,
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun insertObject(o: HomeObject) {
        val v = placementValues(o.placement).apply {
            put("id", o.id)
            put("station_id", o.stationId)
            put("kind", o.kind.name)
            put("gear_id", o.gear?.id)
            put("text", o.text)
            put("created_at_ms", o.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("home_pin", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun updatePlacement(objectId: String, p: Placement) {
        writableDatabase.update("home_pin", placementValues(p), "id = ?", arrayOf(objectId))
    }

    @Synchronized
    fun deleteObject(objectId: String) {
        writableDatabase.delete("home_pin", "id = ?", arrayOf(objectId))
    }

    /** Ids of stash items that currently stand somewhere in a home base. */
    @Synchronized
    fun placedGear(): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT p.gear_id, s.name FROM home_pin p JOIN home_station s ON s.id = p.station_id WHERE p.gear_id IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    // ------------------------------------------------------------------ stash

    @Synchronized
    fun stash(): List<GearItem> {
        val out = ArrayList<GearItem>()
        readableDatabase.query("gear_item", null, null, null, null, null, "created_at_ms ASC").use { c ->
            while (c.moveToNext()) {
                val type = enumOrNull<GearType>(c.getString(c.getColumnIndexOrThrow("base_type"))) ?: continue
                out.add(
                    GearItem(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        type = type,
                        tier = enumOr(c.getString(c.getColumnIndexOrThrow("tier")), GearTier.STATIC),
                        origin = c.getString(c.getColumnIndexOrThrow("origin")),
                        prototype = c.getInt(c.getColumnIndexOrThrow("prototype")) != 0,
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun insertGear(g: GearItem) {
        val v = ContentValues().apply {
            put("id", g.id)
            put("base_type", g.type.name)
            put("tier", g.tier.name)
            put("origin", g.origin)
            put("prototype", if (g.prototype) 1 else 0)
            put("created_at_ms", g.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("gear_item", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes a stash item and takes it off any stand it was on. */
    @Synchronized
    fun deleteGear(gearId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("home_pin", "gear_id = ?", arrayOf(gearId))
            db.delete("gear_item", "id = ?", arrayOf(gearId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun placementValues(p: Placement) = ContentValues().apply {
        put("rel_azimuth_deg", p.relAzimuthDeg)
        put("elevation_deg", p.elevationDeg)
        put("distance_m", p.distanceM)
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

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T = enumOrNull<T>(name) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        try { enumValueOf<T>(name) } catch (e: IllegalArgumentException) { null }
}
