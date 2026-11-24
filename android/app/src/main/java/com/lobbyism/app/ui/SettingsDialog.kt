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
    /** Default edge length of the printed marker (cm), shown when nothing was measured. */
    private val defaultMarkerCm: Double,
    /** The measured marker size changed (cm). */
    private val onMarkerSize: (Double) -> Unit,
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
        val markerLabel = TextView(activity).apply {
            text = "Marker: Kante des schwarzen Quadrats, nachgemessen in cm"
            setPadding(0, Theme.dp(activity, 12), 0, 0)
        }
        val marker = EditText(activity).apply {
            val cm = settings.markerSizeCm.takeIf { it > 0f }?.toDouble() ?: defaultMarkerCm
            setText("%.1f".format(java.util.Locale.GERMANY, cm))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
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
            views = listOf(url, key, info, markerLabel, marker, rotate, restartTutorial),
            confirm = "Speichern",
            dismiss = "Schließen",
        ) {
            settings.serverUrl = url.text.toString()
            settings.serverKey = key.text.toString()
            nodes.requestSync()
            // Accept "15,8" and "15.8"; ignore nonsense outside a printable range.
            val cm = marker.text.toString().replace(',', '.').toDoubleOrNull()
            if (cm != null && cm in MARKER_MIN_CM..MARKER_MAX_CM) {
                settings.markerSizeCm = cm.toFloat()
                onMarkerSize(cm)
            }
        }
    }

    private companion object {
        const val MARKER_MIN_CM = 3.0
        const val MARKER_MAX_CM = 100.0
    }
}
