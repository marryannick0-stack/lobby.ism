package com.lobbyism.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.Geo
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
class NodeStore(context: Context) : SQLiteOpenHelper(context, "lobbyism.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Future versions add migrations here.
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
