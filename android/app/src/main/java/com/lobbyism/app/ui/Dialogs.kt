package com.lobbyism.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.lobbyism.app.ui.theme.Theme

/**
 * All system dialogs of the app go through here, so every one of them
 * restores full-screen mode when it closes and they all look alike.
 */
class Dialogs(private val activity: Activity, private val onClosed: () -> Unit) {

    /** Multi-line text input with a confirm button. Empty input is passed on trimmed (""). */
    fun textInput(
        title: String,
        message: String?,
        hint: String,
        maxChars: Int,
        confirm: String,
        onConfirm: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(maxChars))
            minLines = 3
            gravity = Gravity.TOP
        }
        val box = FrameLayout(activity).apply {
            setPadding(Theme.dp(activity, 20), Theme.dp(activity, 8), Theme.dp(activity, 20), 0)
            addView(input)
        }
        builder(title)
            .apply { if (message != null) setMessage(message) }
            .setView(box)
            .setPositiveButton(confirm) { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** One single-line input of [fields]. */
    class Field(val hint: String, val maxChars: Int)

    /**
     * Explanatory text plus several single-line inputs.
     * [onConfirm] receives the trimmed values in the order of [fields].
     */
    fun fields(
        title: String,
        info: String?,
        fields: List<Field>,
        confirm: String,
        onConfirm: (List<String>) -> Unit,
    ) {
        val inputs = fields.map { f ->
            EditText(activity).apply {
                hint = f.hint
                setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(f.maxChars))
            }
        }
        val views = ArrayList<View>()
        if (info != null) {
            views.add(
                android.widget.TextView(activity).apply {
                    text = info
                    setPadding(0, 0, 0, Theme.dp(activity, 8))
                },
            )
        }
        views.addAll(inputs)
        form(title, views, confirm, "Abbrechen") { onConfirm(inputs.map { it.text.toString().trim() }) }
    }

    /** A list of choices. */
    fun choose(title: String, items: List<String>, cancelable: Boolean = true, onChoice: (Int) -> Unit) {
        builder(title)
            .setItems(items.toTypedArray()) { _, which -> onChoice(which) }
            .apply { if (cancelable) setNegativeButton("Abbrechen", null) }
            .show()
    }

    /** Yes/no question for destructive actions. */
    fun confirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        builder(title)
            .setMessage(message)
            .setPositiveButton(confirm) { _, _ -> onConfirm() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** A form built from arbitrary views, stacked vertically. Returns the dialog so buttons can close it. */
    fun form(
        title: String,
        views: List<View>,
        confirm: String?,
        dismiss: String,
        onConfirm: () -> Unit = {},
    ): AlertDialog {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(activity, 20), Theme.dp(activity, 8), Theme.dp(activity, 20), 0)
            views.forEach(::addView)
        }
        return builder(title)
            .setView(box)
            .apply { if (confirm != null) setPositiveButton(confirm) { _, _ -> onConfirm() } }
            .setNegativeButton(dismiss, null)
            .show()
    }

    /** Message the player must acknowledge (cannot be dismissed by tapping outside). */
    fun notice(title: String, message: String, button: String, onOk: () -> Unit) {
        builder(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(button) { _, _ -> onOk() }
            .show()
    }

    fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun builder(title: String) = AlertDialog.Builder(activity)
        .setTitle(title)
        .setOnDismissListener { onClosed() }
}
