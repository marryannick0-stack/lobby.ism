package com.lobbyism.app.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * All sounds are synthesised in code at start-up: no audio files, no licences.
 *
 * Sonic identity (GDD "Sonic Identity"): every sound is a signal, as if it came
 * through an encrypted, slightly degraded channel. No music, no stingers.
 */
class SignalAudio : SensoryFeedback.Listener {
    private val tracks = HashMap<SensoryFeedback.Cue, AudioTrack>()

    init {
        try {
            tracks[SensoryFeedback.Cue.NODE_MATERIALIZED] = build(sonarPing(freq = 310.0, lengthS = 0.55))
            tracks[SensoryFeedback.Cue.LOCK_TICK] = build(tick())
            tracks[SensoryFeedback.Cue.LOCK_COMPLETE] = build(lockIn())
            tracks[SensoryFeedback.Cue.NODE_PLACED] = build(sonarPing(freq = 220.0, lengthS = 0.8))
            tracks[SensoryFeedback.Cue.DENIED] = build(noiseBurst())
        } catch (e: Exception) {
            // No audio output available: the app stays silent.
            release()
        }
    }

    override fun onCue(cue: SensoryFeedback.Cue, intensity: Float) {
        val t = tracks[cue] ?: return
        try {
            // A static track must be stopped and rewound before it can play again.
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(0.35f + 0.65f * intensity)
            t.play()
        } catch (e: IllegalStateException) {
            // Track was released while a cue was in flight.
        }
    }

    fun release() {
        for (t in tracks.values) {
            try {
                t.release()
            } catch (e: Exception) {
                // Nothing to clean up.
            }
        }
        tracks.clear()
    }

    private fun build(samples: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    /** Low, short sonar ping with a quickly dying echo, slightly overdriven. */
    private fun sonarPing(freq: Double, lengthS: Double): ShortArray {
        val n = (SAMPLE_RATE * lengthS).toInt()
        val dry = DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            sin(2 * PI * freq * t) * exp(-t * 14.0) + 0.25 * sin(2 * PI * freq * 2.01 * t) * exp(-t * 22.0)
        }
        val echoDelay = (SAMPLE_RATE * 0.13).toInt()
        return ShortArray(n) { i ->
            var s = dry[i]
            if (i >= echoDelay) s += dry[i - echoDelay] * 0.32
            if (i >= echoDelay * 2) s += dry[i - echoDelay * 2] * 0.10
            s += (Random.nextDouble() - 0.5) * 0.015 // channel hiss
            toPcm(tanh(s * 1.8) * 0.8)
        }
    }

    /** Tiny mechanical click for the lock ratchet. */
    private fun tick(): ShortArray {
        val n = (SAMPLE_RATE * 0.03).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            toPcm(sin(2 * PI * 1800.0 * t) * exp(-t * 220.0) * 0.5)
        }
    }

    /** Heavy "snap into place": low thud plus a hard click at the start. */
    private fun lockIn(): ShortArray {
        val n = (SAMPLE_RATE * 0.28).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val thud = sin(2 * PI * (95.0 - 60.0 * t) * t) * exp(-t * 16.0)
            val click = sin(2 * PI * 2400.0 * t) * exp(-t * 300.0) * 0.6
            toPcm(tanh((thud + click) * 2.2) * 0.85)
        }
    }

    /** Short burst of static: "that did not work". */
    private fun noiseBurst(): ShortArray {
        val n = (SAMPLE_RATE * 0.12).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            toPcm((Random.nextDouble() - 0.5) * exp(-t * 30.0) * 0.7)
        }
    }

    private fun toPcm(v: Double): Short = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
