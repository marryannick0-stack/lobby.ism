package com.lobbyism.app.data

import android.content.Context
import android.util.Log
import com.lobbyism.app.core.Config
import com.lobbyism.app.core.TutorialConfig
import org.json.JSONObject

/**
 * Reads assets/config.json into a [Config]. Missing or broken keys fall back
 * to the defaults in [Config], so a typo in the file can never crash the app.
 */
object ConfigLoader {
    private const val TAG = "LOBBYISM/Config"

    fun load(context: Context): Config {
        val d = Config()
        val j = try {
            context.assets.open("config.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            Log.w(TAG, "config.json unreadable, using defaults", e)
            return d
        }
        return Config(
            materializeRadiusM = j.optDouble("materializeRadiusM", d.materializeRadiusM),
            materializeRadiusMaxM = j.optDouble("materializeRadiusMaxM", d.materializeRadiusMaxM),
            materializeHysteresis = j.optDouble("materializeHysteresis", d.materializeHysteresis),
            pillarMaxDistanceM = j.optDouble("pillarMaxDistanceM", d.pillarMaxDistanceM),
            scanRadiusM = j.optDouble("scanRadiusM", d.scanRadiusM),
            pinDistanceM = j.optDouble("pinDistanceM", d.pinDistanceM),
            pinDistanceMinM = j.optDouble("pinDistanceMinM", d.pinDistanceMinM),
            pinDistanceMaxM = j.optDouble("pinDistanceMaxM", d.pinDistanceMaxM),
            cameraHeightM = j.optDouble("cameraHeightM", d.cameraHeightM),
            nodeHoverM = j.optDouble("nodeHoverM", d.nodeHoverM),
            lockAngleDeg = j.optDouble("lockAngleDeg", d.lockAngleDeg),
            lockTimeS = j.optDouble("lockTimeS", d.lockTimeS),
            lockDecayS = j.optDouble("lockDecayS", d.lockDecayS),
            headingMinSpeedMps = j.optDouble("headingMinSpeedMps", d.headingMinSpeedMps),
            headingMaxCourseAccuracyDeg = j.optDouble("headingMaxCourseAccuracyDeg", d.headingMaxCourseAccuracyDeg),
            headingSamplesNeeded = j.optInt("headingSamplesNeeded", d.headingSamplesNeeded),
            headingSmoothing = j.optDouble("headingSmoothing", d.headingSmoothing),
            gpsIntervalMs = j.optLong("gpsIntervalMs", d.gpsIntervalMs),
            placeMaxAccuracyM = j.optDouble("placeMaxAccuracyM", d.placeMaxAccuracyM),
            positionSmoothing = j.optDouble("positionSmoothing", d.positionSmoothing),
            textMaxChars = j.optInt("textMaxChars", d.textMaxChars),
            syncIntervalS = j.optLong("syncIntervalS", d.syncIntervalS),
            httpTimeoutMs = j.optInt("httpTimeoutMs", d.httpTimeoutMs),
            layerScale = j.optDouble("layerScale", d.layerScale),
            nodePointCount = j.optInt("nodePointCount", d.nodePointCount),
            nodeSizeM = j.optDouble("nodeSizeM", d.nodeSizeM),
            homePinDistanceM = j.optDouble("homePinDistanceM", d.homePinDistanceM),
            homeNodeSizeM = j.optDouble("homeNodeSizeM", d.homeNodeSizeM),
            gearStandHeightM = j.optDouble("gearStandHeightM", d.gearStandHeightM),
            homeGearDefaultDistanceM = j.optDouble("homeGearDefaultDistanceM", d.homeGearDefaultDistanceM),
            homeGearMinDistanceM = j.optDouble("homeGearMinDistanceM", d.homeGearMinDistanceM),
            homeGearMaxDistanceM = j.optDouble("homeGearMaxDistanceM", d.homeGearMaxDistanceM),
            gearTierGlitch = doubles(j.optJSONArray("gearTierGlitch"), d.gearTierGlitch),
            stashSlots = j.optInt("stashSlots", d.stashSlots),
            tutorial = loadTutorial(j.optJSONObject("tutorial"), d.tutorial),
        )
    }

    /** A JSON number array, or the fallback if missing or malformed. */
    private fun doubles(a: org.json.JSONArray?, fallback: List<Double>): List<Double> {
        if (a == null || a.length() == 0) return fallback
        return try {
            List(a.length()) { a.getDouble(it) }
        } catch (e: Exception) {
            fallback
        }
    }

    private fun loadTutorial(j: JSONObject?, d: TutorialConfig): TutorialConfig {
        if (j == null) return d
        return TutorialConfig(
            gpsGoodAccuracyM = j.optDouble("gpsGoodAccuracyM", d.gpsGoodAccuracyM),
            minWalkSpeedMps = j.optDouble("minWalkSpeedMps", d.minWalkSpeedMps),
            leaveMinDistanceM = j.optDouble("leaveMinDistanceM", d.leaveMinDistanceM),
            findAngleDeg = j.optDouble("findAngleDeg", d.findAngleDeg),
        )
    }
}
