package com.lobbyism.app.core

/**
 * Content types of the node system (GDD v4, chapter 04).
 * Phase 0a renders TEXT only; the others exist so stored data stays valid
 * when their renderers arrive.
 */
enum class ContentType { TEXT, IMAGE, AUDIO, VIDEO, LORE, LOOT, ART_INSTALLATION, COMMUNITY_EVENT, COMBAT_SPAWN }

/** Moderation state. Present from day one so no schema migration is needed later. */
enum class ContentStatus { DRAFT, PENDING_REVIEW, LIVE, FLAGGED, REMOVED }

/**
 * A piece of content anchored to a real GPS position.
 * Mirrors the server table one to one.
 */
data class Node(
    val id: String,
    val lat: Double,
    val lon: Double,
    val contentType: ContentType,
    /** For TEXT: the message itself. Other types will carry a JSON payload. */
    val payload: String,
    /** null = system node, otherwise the creator's user id. */
    val ownerId: String?,
    val status: ContentStatus,
    val createdAtMs: Long,
    /** false while the node only exists on this phone. */
    val synced: Boolean,
)
