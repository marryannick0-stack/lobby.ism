package com.lobbyism.app.render

import com.lobbyism.app.core.GearType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Point clouds for armour stands. Vertex layout as in [NodeGeometry]: x, y, z, seed.
 *
 * Local frame of a stand: x = the figure's right, y = its FRONT, z = up.
 * Units are metres for a figure of [FIGURE_HEIGHT_M], centred on the origin
 * (feet at -half height, top of the head at +half height). The renderer
 * scales it to the configured stand height and turns the front to the player.
 *
 * Art Constraints the shapes follow:
 *  - Fixed body zones: OFFENSE upper body, DEFENSE torso, UTILITY hips/ground (1).
 *  - Items in one slot differ by FORM and position, never by colour (2).
 *  - DEFENSE: Reactive Armor adds material, Ghost Protocol removes it,
 *    Noise Filter makes the outline calmer and cleaner (2).
 *  - Low-poly / sparse is the aesthetic and the budget for the Galaxy A50 (13).
 */
object GearGeometry {
    const val FIGURE_HEIGHT_M = 1.75f
    private const val H = FIGURE_HEIGHT_M / 2f

    // Body landmarks (z relative to the centre).
    private const val FEET_Z = -H
    private const val HIP_Z = -0.03f
    private const val TORSO_BOTTOM_Z = 0.02f
    private const val TORSO_TOP_Z = 0.50f
    private const val SHOULDER_Z = 0.47f
    private const val HEAD_Z = 0.73f
    private const val HEAD_R = 0.11f
    private const val TORSO_HALF_X = 0.19f
    private const val TORSO_HALF_Y = 0.10f

    /**
     * The neutral figure the gear hangs on. [ghostTorso] thins the torso out
     * (Ghost Protocol is subtractive: the stand itself shows less material).
     */
    fun stand(pointCount: Int, ghostTorso: Boolean, seed: Int = 21): FloatArray {
        val b = Builder(seed)
        val torsoShare = if (ghostTorso) 0.06f else 0.30f
        b.box(
            (pointCount * torsoShare).toInt(), -TORSO_HALF_X, TORSO_HALF_X, -TORSO_HALF_Y, TORSO_HALF_Y,
            TORSO_BOTTOM_Z, TORSO_TOP_Z, jitter = 0.01f,
        )
        b.sphere((pointCount * 0.12f).toInt(), 0f, 0f, HEAD_Z, HEAD_R)
        b.tube((pointCount * 0.04f).toInt(), 0f, 0f, TORSO_TOP_Z, 0f, 0f, HEAD_Z - HEAD_R, 0.035f) // neck
        for (side in floatArrayOf(-1f, 1f)) {
            b.tube((pointCount * 0.10f).toInt(), side * 0.23f, 0f, SHOULDER_Z, side * 0.30f, 0.02f, -0.05f, 0.04f) // arm
            b.tube((pointCount * 0.12f).toInt(), side * 0.10f, 0f, HIP_Z, side * 0.12f, 0f, FEET_Z + 0.02f, 0.05f) // leg
        }
        // Stand foot: a small cross on the floor, so it reads as a stand and not as a person.
        b.line((pointCount * 0.03f).toInt(), -0.22f, 0f, FEET_Z, 0.22f, 0f, FEET_Z)
        b.line((pointCount * 0.03f).toInt(), 0f, -0.22f, FEET_Z, 0f, 0.22f, FEET_Z)
        return b.build()
    }

    /** The gear piece itself, in its fixed body zone. */
    fun part(type: GearType, pointCount: Int, seed: Int = 33): FloatArray {
        val b = Builder(seed + type.ordinal)
        when (type) {
            // --- OFFENSE: upper body, three different places ---
            GearType.SCANNER_IMPLANT -> {
                // Head band plus two antennae: "receiving".
                b.ring((pointCount * 0.45f).toInt(), 0f, 0f, HEAD_Z + 0.02f, HEAD_R + 0.03f, 0.015f)
                b.line((pointCount * 0.2f).toInt(), -0.08f, 0f, HEAD_Z + 0.06f, -0.14f, 0.02f, HEAD_Z + 0.32f)
                b.line((pointCount * 0.2f).toInt(), 0.08f, 0f, HEAD_Z + 0.06f, 0.14f, 0.02f, HEAD_Z + 0.32f)
                b.sphere((pointCount * 0.075f).toInt(), -0.14f, 0.02f, HEAD_Z + 0.33f, 0.025f)
                b.sphere((pointCount * 0.075f).toInt(), 0.14f, 0.02f, HEAD_Z + 0.33f, 0.025f)
            }
            GearType.OVERCHARGE_AMP -> {
                // Dense sleeves on both forearms and charged hands: "radiating".
                for (side in floatArrayOf(-1f, 1f)) {
                    b.tube((pointCount * 0.3f).toInt(), side * 0.27f, 0.01f, 0.2f, side * 0.30f, 0.02f, -0.02f, 0.065f)
                    b.sphere((pointCount * 0.2f).toInt(), side * 0.30f, 0.03f, -0.07f, 0.06f)
                }
            }
            GearType.SIGNAL_BOOSTER -> {
                // One block on the RIGHT shoulder only: asymmetric by design.
                b.box((pointCount * 0.7f).toInt(), 0.18f, 0.36f, -0.08f, 0.08f, SHOULDER_Z - 0.04f, SHOULDER_Z + 0.12f, 0.004f)
                b.line((pointCount * 0.3f).toInt(), 0.30f, 0f, SHOULDER_Z + 0.12f, 0.34f, 0f, SHOULDER_Z + 0.34f)
            }

            // --- DEFENSE: torso, three opposite directions ---
            GearType.REACTIVE_ARMOR -> {
                // ADDITIVE: overlapping plates front and back, standing off the body.
                for (row in 0 until 4) {
                    val z0 = TORSO_BOTTOM_Z + 0.02f + row * 0.115f
                    for (face in floatArrayOf(-1f, 1f)) {
                        b.plate(
                            (pointCount / 8f).toInt(), -TORSO_HALF_X - 0.04f, TORSO_HALF_X + 0.04f,
                            face * (TORSO_HALF_Y + 0.05f + row * 0.008f), z0, z0 + 0.10f,
                        )
                    }
                }
            }
            GearType.GHOST_PROTOCOL -> {
                // SUBTRACTIVE: only a few faint fragments where the torso should be.
                b.box((pointCount * 0.35f).toInt(), -TORSO_HALF_X, TORSO_HALF_X, -TORSO_HALF_Y, TORSO_HALF_Y, TORSO_BOTTOM_Z, TORSO_TOP_Z, 0.05f)
            }
            GearType.NOISE_FILTER -> {
                // QUALITATIVE: a clean, exact outline of the torso, no jitter at all.
                b.boxEdges(pointCount, -TORSO_HALF_X - 0.02f, TORSO_HALF_X + 0.02f, -TORSO_HALF_Y - 0.02f, TORSO_HALF_Y + 0.02f, TORSO_BOTTOM_Z, TORSO_TOP_Z + 0.02f)
            }

            // --- UTILITY: hips / ground ---
            GearType.TRAP_BEACON -> {
                // Radiates DOWN and OUT: rings on the floor around the feet.
                b.ring((pointCount * 0.25f).toInt(), 0f, 0f, FEET_Z + 0.01f, 0.30f, 0.008f)
                b.ring((pointCount * 0.35f).toInt(), 0f, 0f, FEET_Z + 0.01f, 0.50f, 0.008f)
                b.ring((pointCount * 0.40f).toInt(), 0f, 0f, FEET_Z + 0.01f, 0.72f, 0.008f)
            }
            GearType.COURIER_NETWORK -> {
                // HANGS ON the body: pouches on both hips and a pack on the back.
                b.box((pointCount * 0.3f).toInt(), -0.30f, -0.20f, -0.06f, 0.06f, HIP_Z - 0.12f, HIP_Z + 0.04f, 0.004f)
                b.box((pointCount * 0.3f).toInt(), 0.20f, 0.30f, -0.06f, 0.06f, HIP_Z - 0.12f, HIP_Z + 0.04f, 0.004f)
                // Lower back only: must not reach torso height, or it would read as DEFENSE (Art Constraints 1).
                b.box((pointCount * 0.4f).toInt(), -0.15f, 0.15f, -TORSO_HALF_Y - 0.14f, -TORSO_HALF_Y - 0.02f, HIP_Z - 0.06f, 0.18f, 0.004f)
            }
        }
        return b.build()
    }

    /** Resonant tier only: a loose shell of particles that the stand "pulls in" (Art Constraints 3). */
    fun aura(pointCount: Int, seed: Int = 55): FloatArray {
        val b = Builder(seed)
        repeat(pointCount) {
            val r = 0.55f + b.rnd.nextFloat() * 0.35f
            val theta = b.rnd.nextFloat() * 2f * PI.toFloat()
            val cz = b.rnd.nextFloat() * 2f - 1f
            val s = sqrt(1f - cz * cz)
            b.point(r * s * cos(theta) * 0.7f, r * s * sin(theta) * 0.7f, r * cz * 1.1f)
        }
        return b.build()
    }

    /** Collects points; every point gets its own random seed for the shader's noise. */
    private class Builder(seed: Int) {
        val rnd = Random(seed)
        private val out = ArrayList<Float>(4096)

        fun point(x: Float, y: Float, z: Float) {
            out.add(x); out.add(y); out.add(z); out.add(rnd.nextFloat() * 100f)
        }

        private fun j(a: Float) = (rnd.nextFloat() - 0.5f) * a

        fun line(n: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, jitter: Float = 0.006f) {
            repeat(n) {
                val t = rnd.nextFloat()
                point(x0 + (x1 - x0) * t + j(jitter), y0 + (y1 - y0) * t + j(jitter), z0 + (z1 - z0) * t + j(jitter))
            }
        }

        /** Points on the surface of a tube between two points. */
        fun tube(n: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float) {
            repeat(n) {
                val t = rnd.nextFloat()
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                // Approximation: offsets in x/y around a mostly vertical axis.
                point(x0 + (x1 - x0) * t + cos(a) * r, y0 + (y1 - y0) * t + sin(a) * r, z0 + (z1 - z0) * t)
            }
        }

        fun sphere(n: Int, cx: Float, cy: Float, cz: Float, r: Float) {
            repeat(n) {
                val theta = rnd.nextFloat() * 2f * PI.toFloat()
                val u = rnd.nextFloat() * 2f - 1f
                val s = sqrt(1f - u * u)
                point(cx + r * s * cos(theta), cy + r * s * sin(theta), cz + r * u)
            }
        }

        /** Horizontal ring. */
        fun ring(n: Int, cx: Float, cy: Float, z: Float, r: Float, jitter: Float) {
            repeat(n) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                point(cx + cos(a) * r + j(jitter), cy + sin(a) * r + j(jitter), z + j(jitter))
            }
        }

        /** Points on the six faces of a box. */
        fun box(n: Int, x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, jitter: Float) {
            repeat(n) {
                var x = x0 + rnd.nextFloat() * (x1 - x0)
                var y = y0 + rnd.nextFloat() * (y1 - y0)
                var z = z0 + rnd.nextFloat() * (z1 - z0)
                when (rnd.nextInt(3)) {
                    0 -> x = if (rnd.nextBoolean()) x0 else x1
                    1 -> y = if (rnd.nextBoolean()) y0 else y1
                    else -> z = if (rnd.nextBoolean()) z0 else z1
                }
                point(x + j(jitter), y + j(jitter), z + j(jitter))
            }
        }

        /** A flat vertical plate facing front (y = const). */
        fun plate(n: Int, x0: Float, x1: Float, y: Float, z0: Float, z1: Float) {
            repeat(n) { point(x0 + rnd.nextFloat() * (x1 - x0), y + j(0.004f), z0 + rnd.nextFloat() * (z1 - z0)) }
        }

        /** The 12 edges of a box, exactly on the line (no jitter). */
        fun boxEdges(n: Int, x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) {
            val xs = floatArrayOf(x0, x1)
            val ys = floatArrayOf(y0, y1)
            val zs = floatArrayOf(z0, z1)
            val per = n / 12
            for (y in ys) for (z in zs) line(per, x0, y, z, x1, y, z, 0f)
            for (x in xs) for (z in zs) line(per, x, y0, z, x, y1, z, 0f)
            for (x in xs) for (y in ys) line(per, x, y, z0, x, y, z1, 0f)
        }

        fun build(): FloatArray = out.toFloatArray()
    }
}
