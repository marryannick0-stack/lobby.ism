package com.lobbyism.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import com.lobbyism.app.core.HomeObject
import com.lobbyism.app.core.Node
import com.lobbyism.app.ui.theme.Theme

/**
 * The card that opens when a lock completes and shows a node's content.
 * The text arrives character by character, like a transmission coming in.
 */
class ContentCard(
    context: Context,
    /** Tapped while a home object is shown: the caller offers to move or remove it. */
    private val onHomeObjectTapped: (objectId: String) -> Unit,
    /** Card text for a home object that is not a plain note (armour stands), or null. */
    private val describeHomeObject: (objectId: String) -> String?,
) : TextView(context) {

    private var animator: ValueAnimator? = null

    /** Id of the node currently shown, or null when closed. */
    var nodeId: String? = null
        private set

    init {
        typeface = Theme.MONO
        textSize = 15f
        setTextColor(Theme.PHOSPHOR)
        setLineSpacing(0f, 1.25f)
        setPadding(Theme.dp(context, 18), Theme.dp(context, 16), Theme.dp(context, 18), Theme.dp(context, 18))
        background = Theme.panel(context)
        visibility = View.GONE
        setOnClickListener {
            val id = nodeId
            if (id != null && HomeObject.isHomeNodeId(id)) onHomeObjectTapped(HomeObject.objectIdOf(id)) else close()
        }
    }

    fun show(node: Node) {
        val age = DateUtils.getRelativeTimeSpanString(node.createdAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val isHome = HomeObject.isHomeNodeId(node.id)
        val origin = when {
            isHome -> "HOME · PRIVAT, NUR AUF DIESEM GERÄT"
            node.synced -> "SERVER"
            else -> "NUR AUF DIESEM GERÄT"
        }
        val action = if (isHome) "[ TIPPEN: VERSCHIEBEN ODER ENTFERNEN ]" else "[ TIPPEN ZUM SCHLIESSEN ]"
        val special = if (isHome) describeHomeObject(HomeObject.objectIdOf(node.id)) else null
        val full = if (special != null) {
            "SIGNAL // GEAR\n\n$special\n\n$origin\n$action"
        } else {
            "SIGNAL // TEXT\n\n${node.payload}\n\n$age · $origin\n$action"
        }
        nodeId = node.id

        visibility = View.VISIBLE
        animator?.cancel()
        animator = ValueAnimator.ofInt(0, full.length).apply {
            duration = (full.length * CHAR_MS).coerceIn(MIN_MS, MAX_MS)
            addUpdateListener { text = full.substring(0, it.animatedValue as Int) }
            start()
        }
    }

    fun close() {
        nodeId = null
        animator?.cancel()
        visibility = View.GONE
    }

    private companion object {
        const val CHAR_MS = 9L
        const val MIN_MS = 250L
        const val MAX_MS = 1400L
    }
}
