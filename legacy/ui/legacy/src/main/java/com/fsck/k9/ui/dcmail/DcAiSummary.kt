package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R

/**
 * DC Mail: summarizes the opened message with Claude.
 */
object DcAiSummary {
    private const val MAX_TEXT_CHARS = 20_000

    private const val SYSTEM_PROMPT =
        "Du fasst E-Mails für den Empfänger zusammen. Antworte auf Deutsch, egal in welcher Sprache die " +
            "E-Mail ist.\n" +
            "Schreibe zuerst in zwei bis drei Sätzen, worum es geht und was der Absender will.\n" +
            "Liste danach unter „Zu tun:“ konkrete Aufgaben, Fragen an mich und Fristen mit Datum auf. " +
            "Gibt es nichts zu tun, schreibe „Zu tun: nichts“.\n" +
            "Erfinde nichts, was nicht in der E-Mail steht. Keine Einleitung, keine Formatierung mit Sternchen."

    @JvmStatic
    fun show(activity: Activity, subject: String?, sender: String?, text: String?) {
        val config = DcAiSettings.load(activity)
        if (config.apiKey.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dcmail_ai_summary_title)
                .setMessage(R.string.dcmail_ai_missing_key)
                .setPositiveButton(R.string.dcmail_ai_settings_title) { _, _ ->
                    DcAiSettingsActivity.start(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val body = text?.trim().orEmpty()
        if (body.isEmpty()) {
            Toast.makeText(activity, R.string.dcmail_ai_summary_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val userMessage = buildString {
            if (!sender.isNullOrBlank()) append("Von: ").append(sender).append("\n")
            if (!subject.isNullOrBlank()) append("Betreff: ").append(subject).append("\n")
            append("\n").append(body.take(MAX_TEXT_CHARS))
        }

        val progress = DcAiReply.showProgress(activity, R.string.dcmail_ai_summary_working)

        Thread {
            val result = try {
                Result.success(DcClaudeClient.complete(config, SYSTEM_PROMPT, userMessage))
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

                result.onSuccess { summary ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.dcmail_ai_summary_title)
                        .setMessage(summary)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.dcmail_ai_copy) { _, _ ->
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Zusammenfassung", summary))
                        }
                        .show()
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
}
