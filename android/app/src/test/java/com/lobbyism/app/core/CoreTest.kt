package com.lobbyism.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTest {
    // Schongau Marienplatz, used as a realistic origin.
    private val lat = 47.8125
    private val lon = 10.8960

    @Test fun distanceOfOneDegreeLatitudeIsAbout111Km() {
        assertEquals(111_195.0, Geo.distanceM(47.0, 10.0, 48.0, 10.0), 50.0)
    }

    @Test fun bearingsToCardinalDirections() {
        assertEquals(0.0, Geo.bearingDeg(lat, lon, lat + 0.001, lon), 0.01)
        assertEquals(90.0, Geo.bearingDeg(lat, lon, lat, lon + 0.001), 0.01)
        assertEquals(180.0, Geo.bearingDeg(lat, lon, lat - 0.001, lon), 0.01)
        assertEquals(270.0, Geo.bearingDeg(lat, lon, lat, lon - 0.001), 0.01)
    }

    @Test fun enuOffsetAgreesWithHaversine() {
        val o = Geo.enuOffsetM(lat, lon, lat + 0.0003, lon + 0.0004)
        val planar = Math.hypot(o[0], o[1])
        assertEquals(Geo.distanceM(lat, lon, lat + 0.0003, lon + 0.0004), planar, 0.05)
        assertTrue(o[0] > 0 && o[1] > 0)
    }

    @Test fun wrapping() {
        assertEquals(350.0, Geo.wrap360(-10.0), 1e-9)
        assertEquals(-10.0, Geo.wrap180(350.0), 1e-9)
        assertEquals(180.0, Geo.wrap180(180.0), 1e-9)
    }
}

class HeadingEstimatorTest {
    private fun estimator() = HeadingEstimator(0.9, 40.0, 6, 0.15)

    @Test fun rejectsSlowAndInaccurateSamples() {
        val h = estimator()
        assertFalse(h.addSample(90.0, 10.0, 0.3, 5.0))
        assertFalse(h.addSample(90.0, 10.0, 1.5, 80.0))
        assertFalse(h.isReady)
    }

    @Test fun convergesOnConstantOffset() {
        val h = estimator()
        repeat(8) { i -> h.addSample(100.0 + i, 20.0 + i, 1.4, 10.0) }
        assertTrue(h.isReady)
        assertEquals(80.0, h.offsetDeg, 0.5)
    }

    @Test fun averagesAcrossTheZeroCrossing() {
        val h = estimator()
        // True offset 0, samples scatter on both sides of 0/360.
        repeat(20) { i -> h.addSample(if (i % 2 == 0) 5.0 else 355.0, 0.0, 1.4, 10.0) }
        val off = Geo.wrap180(h.offsetDeg)
        assertTrue("offset was $off", abs(off) < 3.0)
    }

    @Test fun contradictorySamplesNeverBecomeReady() {
        val h = estimator()
        repeat(40) { i -> h.addSample((i * 97.0) % 360.0, 0.0, 1.4, 10.0) }
        assertFalse(h.isReady)
    }
}

class WorldTrackerTest {
    private val cfg = Config()
    private val lat = 47.8125
    private val lon = 10.8960
    private val north = floatArrayOf(0f, 1f, 0f)
    private val up = floatArrayOf(0f, 0f, 1f)

    private fun node(id: String, dNorthM: Double, dEastM: Double = 0.0) = Node(
        id, lat + dNorthM / 111_195.0, lon + dEastM / (111_195.0 * Math.cos(Math.toRadians(lat))),
        ContentType.TEXT, "hallo", null, ContentStatus.LIVE, 0L, true,
    )

    @Test fun nothingWithoutPosition() {
        val f = WorldTracker(cfg).update(0.016f, north, up, null, null, 5.0, 0.0, listOf(node("a", 1.0)))
        assertTrue(f.views.isEmpty())
    }

    @Test fun nodeAtOwnPositionMaterialisesStraightAheadEvenWithoutNorth() {
        val t = WorldTracker(cfg)
        // Player faces "east" of the sensor frame; north is unknown.
        val east = floatArrayOf(1f, 0f, 0f)
        val f = t.update(0.016f, east, up, lat, lon, 12.0, null, listOf(node("a", 0.0)))
        val v = f.views.single()
        assertEquals(WorldTracker.Stage.MATERIALIZED, v.stage)
        assertEquals(cfg.pinDistanceM.toFloat(), v.pos[0], 1e-3f)
        assertEquals(0f, v.pos[1], 1e-3f)
        assertTrue(f.events.any { it is WorldTracker.Event.Materialized })
    }

    @Test fun pinStaysPutWhenGpsJitters() {
        val t = WorldTracker(cfg)
        val n = listOf(node("a", 0.0))
        val first = t.update(0.016f, north, up, lat, lon, 8.0, null, n).views.single().pos.copyOf()
        // GPS jumps 6 m, still inside the radius: the node must not move.
        val second = t.update(0.016f, north, up, lat + 6.0 / 111_195.0, lon, 8.0, null, n).views.single().pos
        assertEquals(first[0], second[0], 1e-6f)
        assertEquals(first[1], second[1], 1e-6f)
    }

    @Test fun farNodeIsHiddenUntilNorthIsKnownThenBecomesPillarInRightDirection() {
        val t = WorldTracker(cfg)
        val n = listOf(node("a", 0.0, 80.0)) // 80 m east
        val blind = t.update(0.016f, north, up, lat, lon, 5.0, null, n)
        assertTrue(blind.views.isEmpty())
        assertEquals(1, blind.hiddenUntilHeading)

        // Sensor frame equals true frame (offset 0): east is +x.
        val seen = t.update(0.016f, north, up, lat, lon, 5.0, 0.0, n).views.single()
        assertEquals(WorldTracker.Stage.PILLAR, seen.stage)
        assertTrue(seen.pos[0] > 50f && abs(seen.pos[1]) < 1f)

        // Sensor frame rotated: true north lies at sensor azimuth 270, so east lies at sensor azimuth 0 (+y).
        val rotated = WorldTracker(cfg).update(0.016f, north, up, lat, lon, 5.0, 90.0, n).views.single()
        assertTrue(rotated.pos[1] > 50f && abs(rotated.pos[0]) < 1f)
    }

    @Test fun lockChargesOnTargetCompletesAndDecays() {
        val t = WorldTracker(cfg)
        val n = listOf(node("a", 0.0))
        // Let it materialise while looking north.
        var f = t.update(2f, north, up, lat, lon, 5.0, null, n)
        val pos = f.views.single().pos
        val len = Math.sqrt((pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]).toDouble()).toFloat()
        val aim = floatArrayOf(pos[0] / len, pos[1] / len, pos[2] / len)

        var locked = false
        var ticks = 0
        repeat(120) {
            f = t.update(1f / 60f, aim, up, lat, lon, 5.0, null, n)
            if (f.events.any { it is WorldTracker.Event.Locked }) locked = true
            ticks += f.events.count { it is WorldTracker.Event.LockTick }
        }
        assertTrue(locked)
        assertEquals(3, ticks)
        assertNotNull(f.focus)
        assertTrue(f.views.single().revealed)
        assertTrue(f.views.single().glitch < 0.2f)

        // Look away: lock decays and is reported lost exactly once.
        val away = floatArrayOf(-aim[0], -aim[1], 0f)
        var lost = 0
        repeat(120) {
            f = t.update(1f / 60f, away, up, lat, lon, 5.0, null, n)
            lost += f.events.count { it is WorldTracker.Event.LockLost }
        }
        assertEquals(1, lost)
        assertNull(f.focus)
        assertEquals(0f, f.views.single().lock, 1e-6f)
    }

    @Test fun leavingTheRadiusUnpinsOnlyBeyondHysteresis() {
        val t = WorldTracker(cfg)
        val n = listOf(node("a", 0.0))
        t.update(0.016f, north, up, lat, lon, 0.0, 0.0, n)
        val radius = cfg.materializeRadiusM
        val inside = t.update(0.016f, north, up, lat + radius * 1.3 / 111_195.0, lon, 0.0, 0.0, n).views.single()
        assertEquals(WorldTracker.Stage.MATERIALIZED, inside.stage)
        val outside = t.update(0.016f, north, up, lat + radius * 2.0 / 111_195.0, lon, 0.0, 0.0, n).views.single()
        assertEquals(WorldTracker.Stage.PILLAR, outside.stage)
    }

    @Test fun nonLiveNodesAreNeverShown() {
        val flagged = node("a", 0.0).copy(status = ContentStatus.FLAGGED)
        assertTrue(WorldTracker(cfg).update(0.016f, north, up, lat, lon, 5.0, 0.0, listOf(flagged)).views.isEmpty())
    }

    @Test fun lookingDownUsesTopEdgeAsForward() {
        val down = floatArrayOf(0f, 0f, -1f)
        val topEast = floatArrayOf(1f, 0f, 0f)
        val dir = WorldTracker.horizontalForward(down, topEast)
        assertEquals(1f, dir[0], 1e-5f)
        assertEquals(0f, dir[1], 1e-5f)
    }
}
