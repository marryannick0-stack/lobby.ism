package com.lobbyism.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The single source of the app's look: colours, type, panels, buttons.
 *
 * Design language (Art Brief v2): an old sonar tube, analog-degraded, never
 * clean sci-fi and never neon. Any change to how the UI looks starts here.
 *
 * Accessibility (Art Constraints 12): colour is never the only carrier of
 * meaning. Every colour below is paired with a shape, marker or position
 * wherever it encodes information.
 */
object Theme {
    /** Pale phosphor green-white: text, outlines, outdoor nodes. */
    const val PHOSPHOR = 0xFFCDFFDB.toInt()
    const val PHOSPHOR_DIM = 0x99CDFFDB.toInt()
    const val PHOSPHOR_OUTLINE = 0xB3CDFFDB.toInt()

    /** Hints and private/home elements. Always paired with a marker or a calmer animation. */
    const val AMBER = 0xFFFFD27A.toInt()

    /** Faint red ghost behind HUD text, like a misaligned colour tube. */
    const val GHOST_RED = 0x55FF5A4A

    const val PANEL_FILL = 0xD9050A08.toInt()
    const val PANEL_FILL_STRONG = 0xE6050A08.toInt()
    const val INK = 0xFF050A08.toInt()
    const val SCRIM = 0xF0000000.toInt()

    val MONO: Typeface = Typeface.MONOSPACE
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Standard size of square icon buttons, dp. */
    const val ICON_BUTTON_DP = 52

    /** Margin of everything to the screen edge, dp. */
    const val EDGE_DP = 14

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Dark, slightly see-through panel with a thin phosphor outline. */
    fun panel(context: Context, strong: Boolean = false) = GradientDrawable().apply {
        setColor(if (strong) PANEL_FILL_STRONG else PANEL_FILL)
        setStroke(dp(context, 1), PHOSPHOR_OUTLINE)
        cornerRadius = 0f
    }

    /** The app's standard button: monospace, outlined panel, no Material ripple or elevation. */
    fun signalButton(context: Context, label: String, textSizeSp: Float = 14f): Button = Button(context).apply {
        text = label
        typeface = MONO_BOLD
        setTextColor(PHOSPHOR)
        textSize = textSizeSp
        letterSpacing = 0.08f
        isAllCaps = false
        background = panel(context)
        setPadding(dp(context, 18), 0, dp(context, 18), 0)
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
    }

    /** Square icon button (home, settings, align). */
    fun iconButton(context: Context, glyph: String, description: String): Button =
        signalButton(context, glyph, 20f).apply {
            contentDescription = description
            setPadding(0, 0, 0, 0)
        }

    fun monoText(context: Context, sizeSp: Float, bold: Boolean = false, color: Int = PHOSPHOR) =
        TextView(context).apply {
            typeface = if (bold) MONO_BOLD else MONO
            textSize = sizeSp
            setTextColor(color)
        }

    fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Layout params for a square icon button anchored to a bottom corner. */
    fun cornerButtonParams(context: Context, gravity: Int, bottomDp: Int) =
        FrameLayout.LayoutParams(dp(context, ICON_BUTTON_DP), dp(context, ICON_BUTTON_DP), Gravity.BOTTOM or gravity).apply {
            val edge = dp(context, EDGE_DP)
            if (gravity and Gravity.START == Gravity.START) leftMargin = edge else rightMargin = edge
            bottomMargin = dp(context, bottomDp)
        }
}
