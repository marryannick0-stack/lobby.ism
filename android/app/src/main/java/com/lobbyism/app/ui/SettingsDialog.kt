package com.lobbyism.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.lobbyism.app.data.NodeRepository
import com.lobbyism.app.data.Settings
import com.lobbyism.app.ui.theme.Theme

/** The settings sheet behind the "≡" button. */
class SettingsDialog(
    private val activity: Activity,
    private val settings: Settings,
    private val nodes: NodeRepository,
    private val dialogs: Dialogs,
    private val onRotateCamera: () -> Unit,
    private val onRestartTutorial: () -> Unit,
) {
    fun show() {
        val url = EditText(activity).apply {
            hint = "https://dein-server:7350"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val key = EditText(activity).apply {
            hint = "Server-Key"
            setText(settings.serverKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val all = nodes.nodes
        val info = TextView(activity).apply {
            text = "Nodes auf diesem Gerät: ${all.size} (${nodes.unsyncedCount} nicht hochgeladen)\n" +
                "Geräte-ID: ${settings.deviceId.take(8)}…\n" +
                "Ohne Server-Adresse bleibt alles lokal auf diesem Gerät."
            setPadding(0, Theme.dp(activity, 12), 0, 0)
        }
        val rotate = Button(activity).apply {
            text = "Kamerabild um 90° drehen"
            setOnClickListener { onRotateCamera() }
        }
        var dialog: AlertDialog? = null
        val restartTutorial = Button(activity).apply {
            text = "Tutorial neu starten"
            setOnClickListener {
                dialog?.dismiss()
                onRestartTutorial()
            }
        }
        dialog = dialogs.form(
            title = "Einstellungen",
            views = listOf(url, key, info, rotate, restartTutorial),
            confirm = "Speichern",
            dismiss = "Schließen",
        ) {
            settings.serverUrl = url.text.toString()
            settings.serverKey = key.text.toString()
            nodes.requestSync()
        }
    }
}
