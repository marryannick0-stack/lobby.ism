package com.lobbyism.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lobbyism.app.core.Tutorial

/**
 * The tutorial card at the top of the screen.
 *
 * Built in code like the rest of the UI: no layout files, no libraries.
 * [show] is cheap to call often; it only touches views whose content changed.
 */
class TutorialOverlay(
    context: Context,
    private val onPrimary: () -> Unit,
    private val onSecondary: () -> Unit,
    private val onSkip: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val header = label(11f, monoBold, DIM)
    private val title = label(19f, monoBold, PHOSPHOR)
    private val body = label(14.5f, mono, PHOSPHOR).apply { setLineSpacing(0f, 1.3f) }
    private val live = label(13f, monoBold, PHOSPHOR)
    private val bar = label(13f, monoBold, PHOSPHOR)
    private val hint = label(13f, mono, AMBER).apply { setLineSpacing(0f, 1.25f) }
    private val primary = button(true).apply { setOnClickListener { onPrimary() } }
    private val secondary = button(false).apply { setOnClickListener { onSecondary() } }
    private val skip = label(11f, mono, DIM).apply {
        text = "TUTORIAL BEENDEN"
        setPadding(0, dp(12), 0, dp(2))
        setOnClickListener { onSkip() }
    }

    private var last: Tutorial.View? = null

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(0xE6050A08.toInt())
            setStroke(dp(1), 0xB3CDFFDB.toInt())
        }
        isClickable = true // touches on the card do not fall through to the camera view

        addView(header)
        addView(title, gap(4))
        addView(body, gap(8))
        addView(live, gap(10))
        addView(bar, gap(2))
        addView(hint, gap(10))
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(primary, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            addView(secondary, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(10) })
        }
        addView(buttons, gap(14))
        addView(skip)
    }

    fun show(v: Tutorial.View) {
        val prev = last
        last = v
        if (prev == v) return

        header.text = if (v.step == Tutorial.Step.DONE) "TUTORIAL" else "TUTORIAL · SCHRITT ${v.index}/${v.total}"
        setIfChanged(title, v.title)
        setIfChanged(body, v.body)
        setOptional(live, v.live)
        setOptional(bar, v.progress?.let(::progressBar))
        setOptional(hint, v.hint?.let { "▸ $it" })
        setOptionalButton(primary, v.primaryButton)
        setOptionalButton(secondary, v.secondaryButton)
        skip.visibility = if (v.step == Tutorial.Step.DONE) View.GONE else View.VISIBLE
    }

    /** Ten blocks; count carries the meaning, not colour (art constraint 12). */
    private fun progressBar(p: Float): String {
        val filled = (p * 10f).toInt().coerceIn(0, 10)
        return "▮".repeat(filled) + "▯".repeat(10 - filled) + "  ${(p * 100).toInt()}%"
    }

    private fun setIfChanged(t: TextView, s: String) {
        if (t.text.toString() != s) t.text = s
    }

    private fun setOptional(t: TextView, s: String?) {
        if (s == null) {
            t.visibility = View.GONE
        } else {
            setIfChanged(t, s)
            t.visibility = View.VISIBLE
        }
    }

    private fun setOptionalButton(b: Button, s: String?) {
        if (s == null) {
            b.visibility = View.GONE
        } else {
            if (b.text.toString() != s) b.text = s
            b.visibility = View.VISIBLE
        }
    }

    private fun label(sizeSp: Float, face: Typeface, color: Int) = TextView(context).apply {
        textSize = sizeSp
        typeface = face
        setTextColor(color)
        letterSpacing = 0.03f
    }

    private fun button(filled: Boolean) = Button(context).apply {
        typeface = monoBold
        textSize = 13.5f
        letterSpacing = 0.08f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (filled) 0xFF050A08.toInt() else PHOSPHOR)
        background = GradientDrawable().apply {
            setColor(if (filled) PHOSPHOR else 0x00000000)
            setStroke(dp(1), PHOSPHOR)
        }
        gravity = Gravity.CENTER
    }

    private fun gap(topDp: Int) =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private companion object {
        const val PHOSPHOR = 0xFFCDFFDB.toInt()
        const val DIM = 0x99CDFFDB.toInt()
        /** Hints use a second hue AND a leading marker, so colour is never the only signal. */
        const val AMBER = 0xFFFFD27A.toInt()
    }
}
