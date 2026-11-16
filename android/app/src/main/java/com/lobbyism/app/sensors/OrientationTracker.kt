package com.lobbyism.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Tracks which way the phone points (3 degrees of freedom: rotation only).
 *
 * Uses Android's GAME_ROTATION_VECTOR: gyroscope + accelerometer fused by the
 * system, explicitly WITHOUT the magnetometer (project rule R2). The result is
 * smooth and immune to magnetic interference from cars, rails and steel, at the
 * price of an arbitrary zero direction, which HeadingEstimator resolves.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val lock = Any()
    private val rotation = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val scratch = FloatArray(16)
    private var hasData = false

    /** false on the rare phone without a gyroscope: AR cannot work there. */
    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // The sensor may deliver 4 or 5 values; the matrix helper wants at most 4.
        val v = if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(scratch, v)
        synchronized(lock) {
            System.arraycopy(scratch, 0, rotation, 0, 16)
            hasData = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Copies the current pose.
     *
     * @param outView 16 floats. Android stores the matrix row by row as
     *   "device -> world". OpenGL reads arrays column by column, which
     *   transposes it into "world -> device". For a rotation that is exactly
     *   the inverse, so the array can be used as the view matrix unchanged.
     * @param outForward camera viewing direction in the sensor frame (the phone's -z axis)
     * @param outTop     direction of the phone's top edge in the sensor frame (the phone's +y axis)
     * @return false until the first sensor reading arrived
     */
    fun snapshot(outView: FloatArray, outForward: FloatArray, outTop: FloatArray): Boolean {
        synchronized(lock) {
            if (!hasData) return false
            System.arraycopy(rotation, 0, outView, 0, 16)
        }
        // Row-major R: world = R * device. Column 2 is the device z axis, column 1 the device y axis.
        outForward[0] = -outView[2]
        outForward[1] = -outView[6]
        outForward[2] = -outView[10]
        outTop[0] = outView[1]
        outTop[1] = outView[5]
        outTop[2] = outView[9]
        return true
    }
}
