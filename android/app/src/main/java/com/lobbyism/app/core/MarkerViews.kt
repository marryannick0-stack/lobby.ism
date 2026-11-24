package com.lobbyism.app.core

/** Turns detected room markers into views the renderer can draw. Pure. */
object MarkerViews {

    fun of(markers: List<CameraGeometry.MarkerPose>, sizeM: Double): List<WorldTracker.NodeView> =
        markers.map { view(it, sizeM) }

    fun view(m: CameraGeometry.MarkerPose, sizeM: Double): WorldTracker.NodeView {
        val r = m.rotation
        // Row-major 3x3 -> column-major 4x4 for OpenGL.
        val gl = FloatArray(16)
        for (row in 0..2) for (col in 0..2) gl[col * 4 + row] = r[row * 3 + col].toFloat()
        gl[15] = 1f
        val node = Node(
            id = ID_PREFIX + m.id, lat = 0.0, lon = 0.0, contentType = ContentType.TEXT,
            payload = "", ownerId = null, status = ContentStatus.LIVE, createdAtMs = 0L, synced = false,
        )
        return WorldTracker.NodeView(
            node, WorldTracker.Stage.MATERIALIZED,
            floatArrayOf(m.position[0].toFloat(), m.position[1].toFloat(), m.position[2].toFloat()),
            m.distanceM, STABLE_GLITCH, 1f, 0f, false,
            lockable = false, baseGlitch = STABLE_GLITCH, markerSizeM = sizeM.toFloat(), rotation = gl,
        )
    }

    private const val ID_PREFIX = "marker:"

    /** A recognised marker is "locked onto reality": almost no glitch. */
    private const val STABLE_GLITCH = 0.12f
}
