package com.lobbyism.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lobbyism.app.ui.home.HomeController
import com.lobbyism.app.ui.theme.Theme

/**
 * The buttons along the bottom edge.
 *
 *   [⌂ / ✕]          [ main action ]          [≡]
 *   [⟲ / ↶]  (home base, once aligned: re-align, or cancel carrying)
 *
 * Stateless: [render] derives every label from the current states, so the
 * buttons can never disagree with what the controllers think (unidirectional flow).
 */
class ActionButtons(
    private val context: Context,
    onMain: () -> Unit,
    onHome: () -> Unit,
    onHomeLong: () -> Unit,
    /** Home base: re-align, or cancel carrying. */
    onSecondary: () -> Unit,
    onSettings: () -> Unit,
) {
    private val main = Theme.signalButton(context, "+ HINTERLASSEN").apply { setOnClickListener { onMain() } }

    private val home = Theme.iconButton(context, HOME_GLYPH, "Home-Base").apply {
        setOnClickListener { onHome() }
        setOnLongClickListener {
            onHomeLong()
            true
        }
    }

    private val secondary = Theme.iconButton(context, ALIGN_GLYPH, "Neu ausrichten").apply {
        visibility = View.GONE
        setOnClickListener { onSecondary() }
    }

    private val settings = Theme.iconButton(context, "≡", "Einstellungen").apply {
        setOnClickListener { onSettings() }
    }

    fun addTo(root: FrameLayout) {
        root.addView(
            main,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Theme.dp(context, Theme.ICON_BUTTON_DP),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = Theme.dp(context, ROW_1_DP) },
        )
        root.addView(settings, Theme.cornerButtonParams(context, Gravity.END, ROW_1_DP))
        root.addView(home, Theme.cornerButtonParams(context, Gravity.START, ROW_1_DP))
        root.addView(secondary, Theme.cornerButtonParams(context, Gravity.START, ROW_2_DP))
    }

    fun render(homeUi: HomeController.Ui, highlightPlace: Boolean) {
        main.text = when {
            !homeUi.active -> if (highlightPlace) "▶ + HINTERLASSEN ◀" else "+ HINTERLASSEN"
            !homeUi.hasStation -> "STATION WÄHLEN"
            !homeUi.aligned -> "AUSRICHTEN"
            homeUi.carrying -> "HIER ABSTELLEN"
            else -> "+ PLATZIEREN"
        }
        home.text = if (homeUi.active) LEAVE_GLYPH else HOME_GLYPH
        home.contentDescription = if (homeUi.active) "Home-Base verlassen" else "Home-Base"
        secondary.visibility = if (homeUi.active && homeUi.hasStation && homeUi.aligned) View.VISIBLE else View.GONE
        secondary.text = if (homeUi.carrying) CANCEL_GLYPH else ALIGN_GLYPH
        secondary.contentDescription = if (homeUi.carrying) "Tragen abbrechen" else "Neu ausrichten"
    }

    private companion object {
        const val HOME_GLYPH = "⌂"
        const val LEAVE_GLYPH = "✕"
        const val ALIGN_GLYPH = "\u27f2"
        const val CANCEL_GLYPH = "\u21b6"
        /** Bottom margins of the two button rows, dp. */
        const val ROW_1_DP = 30
        const val ROW_2_DP = 92
    }
}
