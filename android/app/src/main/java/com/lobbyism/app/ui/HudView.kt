package com.lobbyism.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.View
import com.lobbyism.app.net.SyncEngine
import com.lobbyism.app.ui.theme.Theme
import kotlin.math.sin

/**
 * Heads-up display drawn over the camera: status lines and the lock reticle.
 *
 * Design language: an old sonar tube, not a clean sci-fi interface. Pale
 * phosphor monospace text with a faint colour ghost, thin broken lines.
 *
 * Accessibility rule of the project (art constraint 12): nothing is told by
 * colour alone. Lock progress is shown by the NUMBER of ring segments and by
 * the brackets closing in, and is doubled by haptics and sound.
 */
class HudView(context: Context) : View(context) {

    /** Immutable snapshot written by the game loop, read here. */
    class State(
        val gpsEnabled: Boolean,
        val hasFix: Boolean,
        val accuracyM: Double,
        val headingReady: Boolean,
        val headingConfidence: Float,
        val nodesInRange: Int,
        val nearestM: Double?,
        val hiddenUntilHeading: Int,
        val sync: SyncEngine.Status,
        val unsynced: Int,
        /** null = nothing in the crosshair. */
        val focusLock: Float?,
        val focusRevealed: Boolean,
        val sensorOk: Boolean,
        /** Tutorial guidance: signed degrees to turn towards the tutorial node, or null. */
        val turnDeg: Double? = null,
        /** Home base status lines; when set they replace the outdoor status. */
        val homeLines: List<String>? = null,
    )

    @Volatile var state: State? = null

    private val density = resources.displayMetrics.density
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.PHOSPHOR
        typeface = mono
        textSize = 12.5f * density
        letterSpacing = 0.06f
        // Dark halo so the text stays readable over a bright sky.
        setShadowLayer(4f * density, 0f, 0f, 0xE6000000.toInt())
    }
    private val ghost = Paint(text).apply {
        color = Theme.GHOST_RED
        clearShadowLayer()
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.PHOSPHOR
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.BUTT
        setShadowLayer(3f * density, 0f, 0f, 0xCC000000.toInt())
    }
    private val dim = Paint(line).apply { alpha = 90 }
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = state
        val t = SystemClock.elapsedRealtime() / 1000f
        if (s != null) {
            drawStatus(canvas, s, t)
            drawReticle(canvas, s, t)
            s.turnDeg?.let { drawTurnHint(canvas, it, t) }
        }
        // Redraw every display frame: the reticle animates and the state changes constantly.
        postInvalidateOnAnimation()
    }

    private fun drawStatus(canvas: Canvas, s: State, t: Float) {
        var top = 18f * density
        var left = 16f * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Keep clear of the camera cut-out.
            rootWindowInsets?.displayCutout?.let {
                top += it.safeInsetTop
                left += it.safeInsetLeft
            }
        }
        val lineHeight = text.textSize * 1.55f
        var y = top + text.textSize
        // A blinking block marks states that need the player to do something.
        val blink = if (sin(t * 6f) > 0f) "█" else " "

        fun row(str: String) {
            canvas.drawText(str, left + 1.2f * density, y, ghost) // faint colour ghost, like a misaligned tube
            canvas.drawText(str, left, y, text)
            y += lineHeight
        }

        val home = s.homeLines
        if (home != null) {
            for (l in home) row(l)
            return
        }

        when {
            !s.sensorOk -> row("KEIN GYROSKOP — GERÄT NICHT GEEIGNET")
            !s.gpsEnabled -> row("$blink STANDORT AUS — IN DEN EINSTELLUNGEN AKTIVIEREN")
            !s.hasFix -> row("$blink SUCHE SIGNAL — INS FREIE GEHEN")
            else -> row("SIGNAL ${bars(s.accuracyM)} ±${s.accuracyM.toInt()} M")
        }

        if (s.hasFix) {
            if (s.headingReady) {
                row("NORD FIX")
            } else {
                val pct = (s.headingConfidence * 100).toInt()
                row("$blink NORD SYNC $pct% — GERADEAUS GEHEN")
            }
            val near = s.nearestM?.let { " · NÄCHSTES ${it.toInt()} M" } ?: ""
            row("NODES ${s.nodesInRange}$near")
            if (s.hiddenUntilHeading > 0) row("${s.hiddenUntilHeading} FERN — RICHTUNG NACH NORD SYNC")
        }

        row(
            when (s.sync) {
                SyncEngine.Status.LOCAL_ONLY -> "NUR LOKAL"
                SyncEngine.Status.OK -> if (s.unsynced > 0) "SERVER OK · ${s.unsynced} WARTEN" else "SERVER OK"
                SyncEngine.Status.ERROR -> "SERVER NICHT ERREICHBAR · ${s.unsynced} WARTEN"
            },
        )
    }

    /** Four bars from the GPS accuracy: purely a readout, never a gameplay penalty (R1). */
    private fun bars(accuracyM: Double): String {
        val n = when {
            accuracyM <= 5 -> 4
            accuracyM <= 10 -> 3
            accuracyM <= 20 -> 2
            else -> 1
        }
        return "▮".repeat(n) + "▯".repeat(4 - n)
    }

    private fun drawReticle(canvas: Canvas, s: State, t: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 30f * density

        // Idle crosshair: four short ticks with a gap in the middle.
        val gap = 7f * density
        val len = 9f * density
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, dim)
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, dim)
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, dim)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, dim)

        val lock = s.focusLock ?: return

        // Target in the cone: corner brackets appear and close in as the lock charges.
        val spread = r * (1.9f - 0.7f * lock)
        val arm = 10f * density
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * spread
                val y = cy + sy * spread
                canvas.drawLine(x, y, x - sx * arm, y, line)
                canvas.drawLine(x, y, x, y - sy * arm, line)
            }
        }

        // Lock ring: four segments, one per quarter of the lock. Count, not colour, carries the meaning.
        arc.set(cx - r, cy - r, cx + r, cy + r)
        val segments = if (s.focusRevealed) 4 else (lock * 4f).toInt().coerceIn(0, 4)
        for (i in 0 until 4) {
            val start = -90f + i * 90f + 8f
            if (i < segments) {
                canvas.drawArc(arc, start, 74f, false, line)
            } else if (i == segments) {
                // The segment currently charging grows and flickers slightly.
                val part = (lock * 4f - segments).coerceIn(0f, 1f)
                dim.alpha = (120 + 60 * sin(t * 30f)).toInt().coerceIn(0, 255)
                canvas.drawArc(arc, start, 74f * part, false, dim)
                dim.alpha = 90
            }
        }
    }

    /**
     * Chevrons left or right of the reticle pointing to where the player has to
     * turn. Disappears once the target is roughly in view.
     */
    private fun drawTurnHint(canvas: Canvas, turnDeg: Double, t: Float) {
        if (kotlin.math.abs(turnDeg) < TURN_HINT_MIN_DEG) return
        val right = turnDeg > 0
        val cx = width / 2f
        val cy = height / 2f
        val dir = if (right) 1f else -1f
        val base = cx + dir * 70f * density
        val size = 11f * density
        // Three chevrons running outwards, like a sweep.
        val phase = (t * 2.2f) % 1f
        for (i in 0 until 3) {
            val x = base + dir * i * 16f * density
            val k = ((phase * 3f - i + 3f) % 3f) / 3f
            line.alpha = (90 + 165 * (1f - k)).toInt()
            canvas.drawLine(x, cy - size, x + dir * size, cy, line)
            canvas.drawLine(x + dir * size, cy, x, cy + size, line)
        }
        line.alpha = 255
        val label = "${kotlin.math.abs(turnDeg).toInt()}°"
        val tw = text.measureText(label)
        val lx = if (right) base + 60f * density else base - 60f * density - tw
        canvas.drawText(label, lx, cy + text.textSize * 0.35f, text)
    }

    private companion object {
        const val TURN_HINT_MIN_DEG = 12.0
    }
}
