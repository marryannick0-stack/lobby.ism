package com.lobbyism.app.net

import android.content.Context
import java.util.UUID

/**
 * Small persistent settings (SharedPreferences).
 *
 * [deviceId] is a random id created on first start. It is deliberately NOT a
 * hardware id: uninstalling the app discards it, and it cannot be used to
 * recognise the phone elsewhere (data minimisation, DSGVO Art. 5).
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("lobbyism", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    var serverKey: String
        get() = prefs.getString("server_key", "") ?: ""
        set(v) = prefs.edit().putString("server_key", v.trim()).apply()

    /** Extra rotation of the camera image in steps of 90 degrees, for devices with unusual sensors. */
    var cameraRotationSteps: Int
        get() = prefs.getInt("camera_rotation_steps", 0)
        set(v) = prefs.edit().putInt("camera_rotation_steps", ((v % 4) + 4) % 4).apply()

    var introSeen: Boolean
        get() = prefs.getBoolean("intro_seen", false)
        set(v) = prefs.edit().putBoolean("intro_seen", v).apply()

    var tutorialDone: Boolean
        get() = prefs.getBoolean("tutorial_done", false)
        set(v) = prefs.edit().putBoolean("tutorial_done", v).apply()

    val deviceId: String
        get() {
            prefs.getString("device_id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            return id
        }
}
