package com.lobbyism.app.core

/**
 * Every tunable value of the client lives here and is loaded from
 * assets/config.json (project rule: never hardcode tunables).
 * The defaults below are only the fallback when a key is missing.
 */
data class Config(
    // --- Node discovery (GDD v4, "Die vier Stufen") ---
    /** Inside this distance a node materialises as an object you can lock. */
    val materializeRadiusM: Double = 8.0,
    /** GPS error is added to the radius up to this cap, so bad GPS never blocks a player (R1). */
    val materializeRadiusMaxM: Double = 25.0,
    /** Leave the materialised state only beyond radius * this factor (prevents flicker). */
    val materializeHysteresis: Double = 1.6,
    /** Sky pillars are drawn for nodes up to this distance. */
    val pillarMaxDistanceM: Double = 150.0,
    /** Nodes further away than this are ignored completely. */
    val scanRadiusM: Double = 300.0,

    // --- Placement of a materialised node in front of the player ---
    val pinDistanceM: Double = 2.5,
    val pinDistanceMinM: Double = 1.5,
    val pinDistanceMaxM: Double = 6.0,
    /** Assumed height of the phone above the ground: defines the virtual ground plane. */
    val cameraHeightM: Double = 1.4,
    /** Height of the node centre above the virtual ground. */
    val nodeHoverM: Double = 1.0,

    // --- Gyro lock ---
    /** Aim error below this counts as "on target". */
    val lockAngleDeg: Double = 7.0,
    /** Seconds on target until the lock is complete. */
    val lockTimeS: Double = 1.1,
    /** Seconds off target until a lock has fully decayed. */
    val lockDecayS: Double = 0.6,

    // --- Heading from GPS course ---
    val headingMinSpeedMps: Double = 0.9,
    val headingMaxCourseAccuracyDeg: Double = 40.0,
    val headingSamplesNeeded: Int = 6,
    val headingSmoothing: Double = 0.15,

    // --- GPS ---
    val gpsIntervalMs: Long = 1000,
    /** Fixes worse than this are shown but not used for placing nodes. */
    val placeMaxAccuracyM: Double = 30.0,
    val positionSmoothing: Double = 0.35,

    // --- Content ---
    val textMaxChars: Int = 500,

    // --- Sync ---
    val syncIntervalS: Long = 20,
    val httpTimeoutMs: Int = 8000,

    // --- Rendering ---
    /** Offscreen layer resolution relative to the screen. Lower = faster on the Galaxy A50. */
    val layerScale: Double = 0.5,
    val nodePointCount: Int = 900,
    val nodeSizeM: Double = 0.45,
)
