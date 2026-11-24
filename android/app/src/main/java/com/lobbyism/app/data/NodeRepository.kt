package com.lobbyism.app.data

import com.lobbyism.app.core.Config
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.Node
import com.lobbyism.app.net.SyncEngine
import java.util.UUID

/**
 * Single source of truth for public nodes (Android architecture guide:
 * "expose application data from the data layer using a repository").
 *
 * The phone's database is the source of truth (offline-first). The server
 * is reached only through [SyncEngine], which this class owns; the UI never
 * talks to the database or the network directly.
 *
 * Thread-safety: [nodes], [unsyncedCount] and [syncStatus] are immutable
 * snapshots behind volatile fields, safe to read from the GL thread every frame
 * (the HUD polls them; nothing needs to be notified).
 */
class NodeRepository(
    private val cfg: Config,
    private val store: NodeStore,
    settings: Settings,
    /** Current position as [lat, lon] for "nodes nearby" queries, or null without a fix. */
    position: () -> DoubleArray?,
) {
    @Volatile var nodes: List<Node> = emptyList()
        private set

    @Volatile var unsyncedCount = 0
        private set

    @Volatile var syncStatus = SyncEngine.Status.LOCAL_ONLY
        private set

    private val sync = SyncEngine(cfg, store, settings, position) { status, changed ->
        syncStatus = status
        if (changed) reload()
    }

    init {
        reload()
    }

    fun startSync() = sync.start()

    fun stopSync() = sync.stop()

    fun requestSync() = sync.requestSync()

    /**
     * Leaves a text node at a position. Stored locally at once, uploaded later.
     * @return the new node
     */
    fun placeText(text: String, lat: Double, lon: Double): Node {
        val node = Node(
            id = UUID.randomUUID().toString(),
            lat = lat,
            lon = lon,
            contentType = ContentType.TEXT,
            payload = text.take(cfg.textMaxChars),
            ownerId = null,
            status = ContentStatus.LIVE,
            createdAtMs = System.currentTimeMillis(),
            synced = false,
        )
        store.insertLocal(node)
        reload()
        sync.requestSync()
        return node
    }

    fun reload() {
        val all = store.all()
        nodes = all
        unsyncedCount = all.count { !it.synced }
    }
}
