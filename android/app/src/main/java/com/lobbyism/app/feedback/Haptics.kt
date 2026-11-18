package com.lobbyism.app.feedback

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Turns feedback cues into vibration patterns. */
class Haptics(context: Context) : SensoryFeedback.Listener {
    @Suppress("DEPRECATION") // VibratorManager needs API 31; this accessor works on every supported version.
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun onCue(cue: SensoryFeedback.Cue, intensity: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (cue) {
            // Rising ratchet: each quarter of the lock ticks a little harder.
            SensoryFeedback.Cue.LOCK_TICK -> oneShot(12, 60 + (intensity * 120).toInt())
            // Solid double thud: the lock snaps in.
            SensoryFeedback.Cue.LOCK_COMPLETE ->
                VibrationEffect.createWaveform(longArrayOf(0, 35, 40, 70), intArrayOf(0, 255, 0, 200), -1)
            SensoryFeedback.Cue.LOCK_LOST -> oneShot(18, 70)
            SensoryFeedback.Cue.NODE_MATERIALIZED -> oneShot(25, 110)
            SensoryFeedback.Cue.NODE_PLACED ->
                VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 20, 60, 60), intArrayOf(0, 120, 0, 170, 0, 255), -1)
            SensoryFeedback.Cue.STEP_DONE ->
                VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 45), intArrayOf(0, 150, 0, 230), -1)
            SensoryFeedback.Cue.DENIED ->
                VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40), intArrayOf(0, 140, 0, 140), -1)
        }
        try {
            v.vibrate(effect)
        } catch (e: Exception) {
            // Vibration is a nicety; never let it break anything.
        }
    }

    private fun oneShot(ms: Long, amplitude: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255))
}
