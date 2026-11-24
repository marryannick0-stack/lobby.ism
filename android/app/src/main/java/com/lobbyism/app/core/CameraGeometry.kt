package com.lobbyism.app.core

/**
 * Geometry between the camera image, the phone body and the world. Pure math.
 *
 * Frames used:
 *  - camera (CV convention, what the marker detector returns): x right, y down,
 *    z forward, in RAW sensor image coordinates.
 *  - device (Android): x right, y up, z out of the screen, phone held upright.
 *  - sensor/world: the frame of GAME_ROTATION_VECTOR (z up), the render frame.
 *
 * Matrices are 3x3, row-major, as DoubleArray(9).
 */
object CameraGeometry {

    /** Pinhole intrinsics in pixels of the analysed image. */
    data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double)

    /**
     * Camera-to-device rotation for the back camera.
     *
     * [sensorOrientationDeg] (CameraCharacteristics.SENSOR_ORIENTATION) is the
     * clockwise angle the raw image must be rotated to appear upright on a
     * phone held in portrait. Upright image axes map to device axes as
     * right = +x, down = -y; the back camera looks along -z.
     */
    fun cameraToDevice(sensorOrientationDeg: Int): DoubleArray {
        val o = ((sensorOrientationDeg % 360) + 360) % 360
        // Where the raw image axes point after the clockwise rotation (y-down image coordinates).
        val (xr, yr) = when (o) {
            0 -> Pair(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
            90 -> Pair(doubleArrayOf(0.0, 1.0), doubleArrayOf(-1.0, 0.0))
            180 -> Pair(doubleArrayOf(-1.0, 0.0), doubleArrayOf(0.0, -1.0))
            270 -> Pair(doubleArrayOf(0.0, -1.0), doubleArrayOf(1.0, 0.0))
            else -> throw IllegalArgumentException("sensor orientation must be a multiple of 90, was $o")
        }
        // Upright image (u right, v down) -> device: u = +x, v = -y.
        fun toDevice(img: DoubleArray) = doubleArrayOf(img[0], -img[1], 0.0)
        val xCam = toDevice(xr)
        val yCam = toDevice(yr)
        val zCam = doubleArrayOf(0.0, 0.0, -1.0)
        // Columns are the camera axes expressed in device coordinates.
        return doubleArrayOf(
            xCam[0], yCam[0], zCam[0],
            xCam[1], yCam[1], zCam[1],
            xCam[2], yCam[2], zCam[2],
        )
    }

    /**
     * Intrinsics for an analysed image of [width] x [height] taken from the
     * sensor's active array of [activeWidth] x [activeHeight], assuming the
     * stream is the centred crop of the active array to its own aspect ratio,
     * scaled down. [calibration] is LENS_INTRINSIC_CALIBRATION (fx, fy, cx, cy, s)
     * in active-array pixels; null or zeros fall back to focal length and sensor size.
     */
    fun intrinsics(
        width: Int,
        height: Int,
        activeWidth: Int,
        activeHeight: Int,
        calibration: FloatArray?,
        focalLengthMm: Float,
        sensorWidthMm: Float,
    ): Intrinsics {
        val streamAspect = width.toDouble() / height
        val activeAspect = activeWidth.toDouble() / activeHeight
        // Size of the crop of the active array that the stream shows.
        val cropW = if (streamAspect < activeAspect) activeHeight * streamAspect else activeWidth.toDouble()
        val cropH = if (streamAspect < activeAspect) activeHeight.toDouble() else activeWidth / streamAspect
        val offX = (activeWidth - cropW) / 2
        val offY = (activeHeight - cropH) / 2
        val scale = width / cropW

        val c = calibration
        if (c != null && c.size >= 4 && c[0] > 0f && c[1] > 0f) {
            return Intrinsics(c[0] * scale, c[1] * scale, (c[2] - offX) * scale, (c[3] - offY) * scale)
        }
        val fPx = focalLengthMm / sensorWidthMm * activeWidth * scale
        return Intrinsics(fPx, fPx, width / 2.0, height / 2.0)
    }

    /** a * b for 3x3 row-major matrices. */
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /** m * v for a 3x3 row-major matrix. */
    fun apply(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /**
     * Device-to-world rotation from OrientationTracker's 4x4 buffer
     * (SensorManager.getRotationMatrixFromVector, row-major, device -> world).
     */
    fun deviceToWorldFrom4x4(m: FloatArray) = doubleArrayOf(
        m[0].toDouble(), m[1].toDouble(), m[2].toDouble(),
        m[4].toDouble(), m[5].toDouble(), m[6].toDouble(),
        m[8].toDouble(), m[9].toDouble(), m[10].toDouble(),
    )

    /** A marker seen by the camera, expressed in the render frame. */
    data class MarkerPose(
        val id: Int,
        /** Marker centre relative to the camera, metres, render (sensor) frame. */
        val position: DoubleArray,
        /** Marker axes in the render frame (columns: x right, y down, z into the wall). Row-major 3x3. */
        val rotation: DoubleArray,
        val distanceM: Double,
    )

    /**
     * Moves a detection from camera to render frame.
     * @param rTag tag -> camera rotation from the detector (row-major)
     * @param tTag tag centre in camera coordinates
     * @param deviceToWorld phone attitude at the time of the frame
     * @param camToDevice from [cameraToDevice]
     */
    fun markerInWorld(
        id: Int,
        rTag: DoubleArray,
        tTag: DoubleArray,
        deviceToWorld: DoubleArray,
        camToDevice: DoubleArray,
    ): MarkerPose {
        val camToWorld = mul(deviceToWorld, camToDevice)
        val pos = apply(camToWorld, tTag)
        val dist = Math.sqrt(tTag[0] * tTag[0] + tTag[1] * tTag[1] + tTag[2] * tTag[2])
        return MarkerPose(id, pos, mul(camToWorld, rTag), dist)
    }
}
