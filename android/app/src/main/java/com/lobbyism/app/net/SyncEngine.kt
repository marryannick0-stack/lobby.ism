package com.lobbyism.app.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lobbyism.app.core.Config
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.Node
import com.lobbyism.app.data.NodeStore
import com.lobbyism.app.data.Settings
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone and the server in step:
 *   1. upload nodes that were placed on this phone,
 *   2. download the nodes around the player.
 *
 * Runs on its own thread. Without a configured server it does nothing and the
 * app works purely locally.
 */
class SyncEngine(
    private val cfg: Config,
    private val store: NodeStore,
    private val settings: Settings,
    /** Current position as [lat, lon], or null without a GPS fix. */
    private val position: () -> DoubleArray?,
    /** Called on the main thread after every attempt. */
    private val onResult: (Status, changed: Boolean) -> Unit,
) {
    enum class Status { LOCAL_ONLY, OK, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    private var client: NakamaClient? = null
    private var clientKey = ""

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ syncOnce() }, 1, cfg.syncIntervalS, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** Sync as soon as possible, e.g. right after placing a node. */
    fun requestSync() {
        executor?.execute { syncOnce() }
    }

    private fun syncOnce() {
        val url = settings.serverUrl
        val key = settings.serverKey
        if (url.isBlank() || key.isBlank()) {
            report(Status.LOCAL_ONLY, false)
            return
        }
        try {
            // Rebuild the client when the settings changed.
            val wanted = "$url|$key"
            if (client == null || clientKey != wanted) {
                client = NakamaClient(url, key, settings.deviceId, cfg.httpTimeoutMs)
                clientKey = wanted
            }
            val c = client!!
            var changed = false

            for (n in store.unsynced()) {
                val res = c.rpc(
                    "node_create",
                    JSONObject()
                        .put("id", n.id)
                        .put("lat", n.lat)
                        .put("lon", n.lon)
                        .put("content_type", n.contentType.name)
                        .put("payload", n.payload)
                        .put("created_at_ms", n.createdAtMs),
                )
                store.markSynced(n.id, res.optString("owner_id").ifEmpty { c.userId })
                changed = true
            }

            val pos = position()
            if (pos != null) {
                val res = c.rpc(
                    "nodes_nearby",
                    JSONObject().put("lat", pos[0]).put("lon", pos[1]).put("radius_m", cfg.scanRadiusM),
                )
                val arr = res.optJSONArray("nodes")
                val nodes = ArrayList<Node>()
                if (arr != null) {
                    for (i in 0 until arr.length()) parseNode(arr.getJSONObject(i))?.let(nodes::add)
                }
                store.applyServerSnapshot(pos[0], pos[1], cfg.scanRadiusM, nodes)
                changed = true
            }
            report(Status.OK, changed)
        } catch (e: Exception) {
            // Network down, wrong address, server error: keep everything local and try again later.
            Log.w(TAG, "sync failed: ${e.message}")
            client = null
            report(Status.ERROR, false)
        }
    }

    private fun parseNode(j: JSONObject): Node? = try {
        Node(
            id = j.getString("id"),
            lat = j.getDouble("lat"),
            lon = j.getDouble("lon"),
            contentType = ContentType.valueOf(j.getString("content_type")),
            payload = j.optString("payload"),
            ownerId = if (j.isNull("owner_id")) null else j.optString("owner_id"),
            status = ContentStatus.valueOf(j.optString("content_status", "LIVE")),
            createdAtMs = j.optLong("created_at_ms"),
            synced = true,
        )
    } catch (e: Exception) {
        // A node of a type this app version does not know yet: skip it, do not fail the whole sync.
        null
    }

    private fun report(status: Status, changed: Boolean) {
        main.post { onResult(status, changed) }
    }

    private companion object {
        const val TAG = "LOBBYISM/Sync"
    }
}
