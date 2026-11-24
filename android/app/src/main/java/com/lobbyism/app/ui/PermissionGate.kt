package com.lobbyism.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.lobbyism.app.data.Settings
import com.lobbyism.app.ui.theme.Theme

/**
 * Everything about runtime permissions: the one-time explanation, the
 * system request, and a full-screen notice while something is missing.
 *
 * The explanation names exactly what each permission is used for and what
 * is NOT done with it (DSGVO Art. 13: inform at the time of collection).
 */
class PermissionGate(
    private val activity: Activity,
    private val settings: Settings,
    private val dialogs: Dialogs,
    /** Main thread: permissions may have changed; (re)start what needs them. */
    private val onPermissionsChanged: () -> Unit,
) {
    /** Full-screen notice; add it on top of everything else. */
    val blocker: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Theme.SCRIM)
        val pad = Theme.dp(activity, 28)
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        isClickable = true // swallow touches meant for the views below
    }

    private val blockerText = Theme.monoText(activity, 15f).apply { gravity = Gravity.CENTER }

    init {
        blocker.addView(blockerText)
        blocker.addView(
            Theme.signalButton(activity, "ZUGRIFF ERLAUBEN").apply { setOnClickListener { onBlockerAction() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Theme.dp(activity, Theme.ICON_BUTTON_DP))
                .apply { topMargin = Theme.dp(activity, 24) },
        )
    }

    val hasCamera: Boolean get() = has(Manifest.permission.CAMERA)
    val hasLocation: Boolean get() = has(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Call once at start-up. */
    fun begin() {
        if (settings.introSeen) {
            requestMissing()
        } else {
            dialogs.notice(
                "LOBBYISM",
                "Diese App legt Inhalte über die echte Welt.\n\n" +
                    "Dafür braucht sie:\n" +
                    "• Kamera: zeigt die Umgebung. Das Bild wird nicht gespeichert und nicht übertragen.\n" +
                    "• Standort: verankert Inhalte an echten Orten. Deine Position wird nur gesendet, " +
                    "wenn du selbst einen Server einträgst.",
                "Weiter",
            ) {
                settings.introSeen = true
                requestMissing()
            }
        }
    }

    /** Forward from Activity.onRequestPermissionsResult. */
    fun onResult(requestCode: Int) {
        if (requestCode != REQUEST_CODE) return
        onPermissionsChanged()
        refresh()
    }

    /** Shows or hides the notice. Call on resume: permissions can change in the system settings. */
    fun refresh() {
        val missing = missing()
        if (missing.isEmpty() || !settings.introSeen) {
            blocker.visibility = View.GONE
            return
        }
        val names = missing.joinToString(" und ") {
            if (it == Manifest.permission.CAMERA) "Kamera" else "genauen Standort"
        }
        blockerText.text = "Ohne $names funktioniert LOBBYISM nicht."
        blocker.visibility = View.VISIBLE
    }

    private fun requestMissing() {
        val missing = missing()
        if (missing.isNotEmpty()) activity.requestPermissions(withCoarse(missing), REQUEST_CODE)
        refresh()
    }

    private fun onBlockerAction() {
        val missing = missing()
        // After "don't ask again" Android no longer shows the dialog: send the player to the app settings instead.
        val permanentlyDenied = missing.any { !activity.shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            activity.startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        } else {
            activity.requestPermissions(withCoarse(missing), REQUEST_CODE)
        }
    }

    private fun missing() = REQUIRED.filter { !has(it) }

    /**
     * Android 12+ ignores a request for the precise location unless the
     * approximate one is requested in the same dialog. The app still needs the
     * precise one: GPS is not available with approximate location only.
     */
    private fun withCoarse(missing: List<String>): Array<String> =
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
            (missing + Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()
        } else {
            missing.toTypedArray()
        }

    private fun has(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val REQUEST_CODE = 1
        private val REQUIRED = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
