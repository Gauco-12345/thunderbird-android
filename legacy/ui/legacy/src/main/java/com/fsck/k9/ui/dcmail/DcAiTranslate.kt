package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R

/**
 * DC Mail: translates the opened message into German with Claude.
 */
object DcAiTranslate {
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_TOKENS = 6000

    private const val SYSTEM_PROMPT =
        "Du übersetzt E-Mails ins Deutsche.\n" +
            "Übersetze vollständig und sinngemäß in natürliches Deutsch, behalte Absätze, Aufzählungen, " +
            "Namen, Zahlen, Beträge, Daten und Links bei. Die Anrede (Du/Sie) richtet sich nach dem Ton " +
            "des Originals, im Zweifel Sie.\n" +
            "Lass lange Signaturen, rechtliche Hinweise und zitierte ältere Mails weg und schreibe " +
            "stattdessen „[…]“.\n" +
            "Gib nur die Übersetzung aus, ohne Einleitung, Erklärung oder Formatierung mit Sternchen. " +
            "Ist der Text bereits deutsch, gib ihn unverändert aus."

    @JvmStatic
    fun show(activity: Activity, subject: String?, text: String?) {
        val config = DcAiSettings.load(activity)
        if (config.apiKey.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dcmail_translate_title)
                .setMessage(R.string.dcmail_ai_missing_key)
                .setPositiveButton(R.string.dcmail_ai_settings_title) { _, _ -> DcAiSettingsActivity.start(activity) }
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
            if (!subject.isNullOrBlank()) append("Betreff: ").append(subject).append("\n\n")
            append(body.take(MAX_TEXT_CHARS))
        }

        val progress = DcAiReply.showProgress(activity, R.string.dcmail_translate_working)
        Thread {
            val result = try {
                Result.success(DcClaudeClient.complete(config, SYSTEM_PROMPT, userMessage, MAX_TOKENS))
            } catch (e: DcAiException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(DcAiException(e.message ?: e.javaClass.simpleName))
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !progress.isShowing) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { translation ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.dcmail_translate_title)
                        .setMessage(translation)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.dcmail_ai_copy) { _, _ ->
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Übersetzung", translation))
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
