package com.lobbyism.app.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds the point clouds. Each vertex is 4 floats: x, y, z, seed.
 * Coordinates are in the sensor frame convention (z = up).
 */
object NodeGeometry {
    const val FLOATS_PER_VERTEX = 4

    /**
     * Node form for Phase 0a: a broken frame around a dense core.
     *  - frame: the 12 edges of a cube, each with gaps ("edges incomplete, parts missing")
     *  - shards: small flat fragments hanging on the faces
     *  - core: a dense cluster in the middle, where the pulse starts
     * Fits in a cube of edge length 1; the renderer scales it to metres.
     */
    fun node(pointCount: Int, seed: Int = 7): FloatArray {
        val rnd = Random(seed)
        val out = ArrayList<Float>(pointCount * FLOATS_PER_VERTEX)
        fun add(x: Float, y: Float, z: Float) {
            out.add(x); out.add(y); out.add(z); out.add(rnd.nextFloat() * 100f)
        }

        val corePoints = pointCount * 30 / 100
        val shardPoints = pointCount * 20 / 100
        val framePoints = pointCount - corePoints - shardPoints

        // --- frame: cube edges with gaps ---
        val h = 0.5f
        val corners = arrayOf(
            floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(-h, h, -h),
            floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
        )
        val edges = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7,
        )
        val perEdge = framePoints / edges.size
        for ((a, b) in edges) {
            val gapStart = rnd.nextFloat() * 0.7f
            val gapLen = 0.12f + rnd.nextFloat() * 0.25f
            var placed = 0
            while (placed < perEdge) {
                val t = rnd.nextFloat()
                if (t > gapStart && t < gapStart + gapLen) continue // the missing piece
                val j = 0.012f
                add(
                    corners[a][0] + (corners[b][0] - corners[a][0]) * t + (rnd.nextFloat() - 0.5f) * j,
                    corners[a][1] + (corners[b][1] - corners[a][1]) * t + (rnd.nextFloat() - 0.5f) * j,
                    corners[a][2] + (corners[b][2] - corners[a][2]) * t + (rnd.nextFloat() - 0.5f) * j,
                )
                placed++
            }
        }

        // --- shards: small flat fragments on random faces ---
        val shardCount = 5
        val perShard = shardPoints / shardCount
        repeat(shardCount) {
            val axis = rnd.nextInt(3)
            val side = if (rnd.nextBoolean()) h else -h
            val cu = (rnd.nextFloat() - 0.5f) * 0.6f
            val cv = (rnd.nextFloat() - 0.5f) * 0.6f
            val size = 0.10f + rnd.nextFloat() * 0.14f
            repeat(perShard) {
                val u = cu + (rnd.nextFloat() - 0.5f) * size
                val v = cv + (rnd.nextFloat() - 0.5f) * size
                when (axis) {
                    0 -> add(side, u, v)
                    1 -> add(u, side, v)
                    else -> add(u, v, side)
                }
            }
        }

        // --- core: dense cluster, denser towards the centre ---
        repeat(pointCount - out.size / FLOATS_PER_VERTEX) {
            val r = 0.20f * rnd.nextFloat() * rnd.nextFloat()
            val theta = rnd.nextFloat() * 2f * PI.toFloat()
            val cz = rnd.nextFloat() * 2f - 1f
            val s = sqrt(1f - cz * cz)
            add(r * s * cos(theta), r * s * sin(theta), r * cz)
        }
        return out.toFloatArray()
    }

    /**
     * Sky pillar: a thin, frayed column of points rising from the ground.
     * Denser near the ground, thinning out towards the top.
     */
    fun pillar(pointCount: Int, heightM: Float, seed: Int = 11): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(pointCount * FLOATS_PER_VERTEX)
        for (i in 0 until pointCount) {
            val t = rnd.nextFloat()
            val z = t * t * heightM
            val spread = 0.10f + 0.25f * t
            out[i * 4] = (rnd.nextFloat() - 0.5f) * spread
            out[i * 4 + 1] = (rnd.nextFloat() - 0.5f) * spread
            out[i * 4 + 2] = z
            out[i * 4 + 3] = rnd.nextFloat() * 100f
        }
        return out
    }

    /**
     * Room marker frame, unit size, in the marker's own plane (z = 0, z points into the wall):
     * a precise outline, inner corner brackets, a centre cross and a faint echo of the
     * outline standing off the wall. Reads as "the system has locked onto this".
     */
    fun markerFrame(seed: Int = 17): FloatArray {
        val rnd = Random(seed)
        val out = ArrayList<Float>(1200 * FLOATS_PER_VERTEX)
        fun add(x: Float, y: Float, z: Float) {
            out.add(x); out.add(y); out.add(z); out.add(rnd.nextFloat() * 100f)
        }
        fun line(n: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float) {
            repeat(n) {
                val t = rnd.nextFloat()
                add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z)
            }
        }
        val h = 0.5f
        // Outline exactly on the black square's edge.
        line(110, -h, -h, h, -h, 0f); line(110, h, -h, h, h, 0f)
        line(110, h, h, -h, h, 0f); line(110, -h, h, -h, -h, 0f)
        // Echo standing 4 cm (relative) off the wall, slightly larger.
        val e = 0.58f
        line(50, -e, -e, e, -e, -0.08f); line(50, e, -e, e, e, -0.08f)
        line(50, e, e, -e, e, -0.08f); line(50, -e, e, -e, -e, -0.08f)
        // Inner corner brackets.
        val b = 0.32f
        val arm = 0.12f
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
            line(30, sx * b, sy * b, sx * (b - arm), sy * b, 0f)
            line(30, sx * b, sy * b, sx * b, sy * (b - arm), 0f)
        }
        // Centre cross.
        line(40, -0.08f, 0f, 0.08f, 0f, 0f)
        line(40, 0f, -0.08f, 0f, 0.08f, 0f)
        return out.toFloatArray()
    }
}
