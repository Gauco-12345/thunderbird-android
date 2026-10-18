package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R

/**
 * DC Mail: turns the user's notes into a finished reply with Claude.
 */
object DcAiReply {
    private const val MAX_ORIGINAL_CHARS = 12_000
    private const val PADDING_DP = 20
    private const val NOTES_MIN_LINES = 3

    private const val BASE_SYSTEM_PROMPT =
        "Du formulierst E-Mails im Namen des Nutzers. Du bist nicht sein Gesprächspartner und " +
            "antwortest nicht dem Nutzer selbst.\n" +
            "Wenn eine Original-E-Mail mitgeschickt wird, hat der Nutzer sie EMPFANGEN und antwortet " +
            "jetzt ihrem Absender. Schreibe aus Sicht des Nutzers in der Ich-Form an diesen Absender.\n" +
            "Setze die Stichpunkte des Nutzers inhaltlich genau um. Erfinde keine Fakten, Zusagen, " +
            "Preise oder Termine.\n" +
            "Antworte in der Sprache der Original-E-Mail, ohne Original-E-Mail auf Deutsch.\n" +
            "Gib ausschließlich den Text der E-Mail aus: keine Betreffzeile, keine Erklärungen, " +
            "keine Platzhalter wie [Name] und keine Signatur, die fügt die App selbst an."

    @JvmStatic
    fun show(
        activity: Activity,
        target: EditText,
        subject: String?,
        sender: String?,
        originalText: String?,
    ) {
        val config = DcAiSettings.load(activity)
        if (config.apiKey.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dcmail_ai_reply)
                .setMessage(R.string.dcmail_ai_missing_key)
                .setPositiveButton(R.string.dcmail_ai_settings_title) { _, _ ->
                    DcAiSettingsActivity.start(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val notesInput = EditText(activity)
        notesInput.hint = activity.getString(R.string.dcmail_ai_notes_hint)
        notesInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        notesInput.minLines = NOTES_MIN_LINES
        notesInput.gravity = Gravity.TOP or Gravity.START
        notesInput.setText(target.text.toString())

        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(notesInput)

        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_ai_reply)
            .setView(container)
            .setPositiveButton(R.string.dcmail_ai_generate) { _, _ ->
                val notes = notesInput.text.toString().trim()
                if (notes.isNotEmpty()) {
                    generate(activity, target, config, notes, subject, sender, originalText)
                }
            }
            .setNeutralButton(R.string.dcmail_ai_settings_title) { _, _ ->
                DcAiSettingsActivity.start(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("LongParameterList")
    private fun generate(
        activity: Activity,
        target: EditText,
        config: DcAiConfig,
        notes: String,
        subject: String?,
        sender: String?,
        originalText: String?,
    ) {
        val system = buildString {
            append(BASE_SYSTEM_PROMPT)
            if (config.instructions.isNotBlank()) {
                append("\n\nZusätzliche Anweisungen des Nutzers:\n")
                append(config.instructions.trim())
            }
        }

        val userMessage = buildString {
            val original = originalText?.trim().orEmpty()
            if (original.isNotEmpty()) {
                append("Original-E-Mail, die ich empfangen habe")
                if (!sender.isNullOrBlank()) append(" (von ").append(sender).append(")")
                append(":\n")
                if (!subject.isNullOrBlank()) append("Betreff: ").append(subject).append("\n")
                append("\n").append(original.take(MAX_ORIGINAL_CHARS)).append("\n\n---\n")
                append("Meine Stichpunkte für die Antwort an den Absender:\n")
            } else {
                if (!subject.isNullOrBlank()) append("Betreff: ").append(subject).append("\n")
                append("Es gibt keine Original-E-Mail. Formuliere eine neue E-Mail aus meinen Stichpunkten:\n")
            }
            append(notes)
        }

        val progress = showProgress(activity)

        Thread {
            val result = try {
                Result.success(DcClaudeClient.complete(config, system, userMessage))
            } catch (e: DcAiException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(DcAiException(e.message ?: e.javaClass.simpleName))
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !progress.isShowing) {
                    return@runOnUiThread
                }
                progress.dismiss()

                result.onSuccess { text ->
                    target.setText(text)
                    target.requestFocus()
                    target.setSelection(target.text.length)
                }.onFailure { error ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.dcmail_ai_error_title)
                        .setMessage(error.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showProgress(activity: Activity): AlertDialog {
        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(padding, padding, padding, padding)
        row.addView(ProgressBar(activity))
        val message = TextView(activity)
        message.setText(R.string.dcmail_ai_working)
        message.setPadding(padding, 0, 0, 0)
        row.addView(message)

        return AlertDialog.Builder(activity)
            .setView(row)
            .setCancelable(true)
            .show()
    }
}
