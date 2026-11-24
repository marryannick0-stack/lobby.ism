package com.lobbyism.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.lobbyism.app.ui.theme.Theme
import kotlin.math.sin
import kotlin.random.Random

/**
 * The start screen. The camera stays OFF here: it only starts once the
 * player picks a mode (privacy and battery).
 *
 * Look: a dead channel. Scanlines, slow rolling bars, a title that never
 * quite holds still. Same signal language as the AR layer (Art Brief v2).
 */
class StartScreen(
    context: Context,
    onOutdoor: () -> Unit,
    onHome: () -> Unit,
    onTutorial: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {

    private val tutorialButton: LinearLayout
    private val tutorialTag = Theme.monoText(context, 10.5f, bold = true, color = Theme.AMBER)
    private val footer = Theme.monoText(context, 10.5f, color = Theme.PHOSPHOR_DIM)

    init {
        setBackgroundColor(Theme.INK)
        isClickable = true // nothing behind the menu reacts to touches
        addView(DeadChannel(context), Theme.matchParent())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = Theme.dp(context, 28)
            setPadding(pad, 0, pad, 0)
        }
        column.addView(
            GlitchTitle(context, "LOBBYISM"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 76)),
        )
        column.addView(
            Theme.monoText(context, 11f, color = Theme.PHOSPHOR_DIM).apply {
                text = "SIGNAL // PHASE 0"
                letterSpacing = 0.3f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Theme.dp(context, 44) },
        )

        column.addView(entry("▶  DRAUSSEN", "Nodes finden, Nachrichten hinterlassen", onOutdoor), entryParams())
        column.addView(entry("⌂  HOME-BASE", "Deine Basis drinnen bauen", onHome), entryParams())
        tutorialButton = entry("◎  TUTORIAL", "Erster Gang, kalibriert alles", onTutorial)
        tutorialButton.addView(tutorialTag)
        column.addView(tutorialButton, entryParams())
        column.addView(entry("≡  EINSTELLUNGEN", "Server, Kamera, Gerät", onSettings), entryParams())

        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(
            footer.apply { gravity = Gravity.CENTER },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { bottomMargin = Theme.dp(context, 24) },
        )
    }

    /** Marks the tutorial as recommended until it was done once. */
    fun render(tutorialDone: Boolean, buildLabel: String) {
        tutorialTag.text = if (tutorialDone) "" else "EMPFOHLEN FÜR DEN ERSTEN START"
        tutorialTag.visibility = if (tutorialDone) View.GONE else View.VISIBLE
        footer.text = "TESTBUILD $buildLabel · OPEN SOURCE · SELBST GEHOSTET"
    }

    /** One menu entry: label, one explanatory line, whole panel tappable. */
    private fun entry(label: String, hint: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.panel(context)
        val p = Theme.dp(context, 16)
        setPadding(p, Theme.dp(context, 14), p, Theme.dp(context, 14))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        contentDescription = "$label. $hint"
        addView(Theme.monoText(context, 16f, bold = true).apply { text = label; letterSpacing = 0.08f })
        addView(Theme.monoText(context, 12f, color = Theme.PHOSPHOR_DIM).apply { text = hint })
    }

    private fun entryParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Theme.dp(context, 12) }

    /** Background: scanlines and slowly rolling interference bars. */
    private class DeadChannel(context: Context) : View(context) {
        private val line = Paint().apply { color = 0x14CDFFDB }
        private val bar = Paint()

        override fun onDraw(canvas: Canvas) {
            val t = SystemClock.elapsedRealtime() / 1000f
            val spacing = 3f * resources.displayMetrics.density
            var y = 0f
            while (y < height) {
                canvas.drawRect(0f, y, width.toFloat(), y + 1f, line)
                y += spacing
            }
            // Two soft bars rolling down at different speeds, like a badly tuned set.
            for (i in 0 until 2) {
                val speed = if (i == 0) 0.07f else 0.031f
                val pos = ((t * speed + i * 0.43f) % 1f) * (height + 200f) - 100f
                val alpha = (18 + 10 * sin(t * 2f + i)).toInt().coerceIn(0, 255)
                bar.color = (alpha shl 24) or 0x00CDFFDB
                canvas.drawRect(0f, pos, width.toFloat(), pos + 60f + i * 40f, bar)
            }
            if (isShown) postInvalidateOnAnimation()
        }
    }

    /** The title: monospace, colour channels slightly apart, occasional sideways tear. */
    private class GlitchTitle(context: Context, private val text: String) : View(context) {
        private val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.PHOSPHOR
            typeface = Theme.MONO_BOLD
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        private val red = Paint(main).apply { color = 0x88FF5A4A.toInt() }
        private val cyan = Paint(main).apply { color = 0x6650E0FF }
        private val rnd = Random(7)
        private var tearUntil = 0L
        private var tear = 0f

        override fun onDraw(canvas: Canvas) {
            val size = height * 0.62f
            main.textSize = size
            red.textSize = size
            cyan.textSize = size
            val now = SystemClock.elapsedRealtime()
            // Every few seconds the signal tears for a moment.
            if (now > tearUntil && rnd.nextFloat() < 0.012f) {
                tearUntil = now + 90 + rnd.nextInt(140)
                tear = (rnd.nextFloat() - 0.5f) * size * 0.25f
            }
            val offset = if (now < tearUntil) tear else 0f
            val cx = width / 2f
            val base = height * 0.78f
            val drift = 1.5f * resources.displayMetrics.density
            canvas.drawText(text, cx - drift + offset, base, red)
            canvas.drawText(text, cx + drift - offset * 0.5f, base, cyan)
            canvas.drawText(text, cx + offset * 0.2f, base, main)
            if (isShown) postInvalidateOnAnimation()
        }
    }
}
