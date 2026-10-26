package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.provider.AttachmentTempFileProvider
import com.fsck.k9.provider.RawMessageProvider
import com.fsck.k9.ui.R
import java.text.DateFormat

/**
 * DC Mail: hands the opened mail (text plus PDF/image attachments) to the Claude app together with a task.
 */
object DcShareToClaude {
    private const val CLAUDE_PACKAGE = "com.anthropic.claude"
    private const val MAX_TEXT_CHARS = 30_000
    private const val PADDING_DP = 24
    private const val MAX_FILE_NAME = 80

    /**
     * @param onIncomplete called when the mail is only partly downloaded; the caller should load the rest first.
     */
    fun show(
        activity: Activity,
        info: MessageViewInfo,
        plainText: String?,
        messageReference: MessageReference,
        onIncomplete: () -> Unit,
    ) {
        if (info.isMessageIncomplete) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dcmail_claude_title)
                .setMessage(R.string.dcmail_claude_incomplete)
                .setPositiveButton(R.string.dcmail_claude_download) { _, _ -> onIncomplete() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val tasks = activity.resources.getStringArray(R.array.dcmail_claude_tasks)
        val input = EditText(activity)
        input.hint = activity.getString(R.string.dcmail_claude_task_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.minLines = 2
        input.gravity = Gravity.TOP or Gravity.START
        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity)
        container.setPadding(padding, padding / 3, padding, 0)
        container.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_claude_title)
            .setView(container)
            .setItems(tasks) { _, which -> share(activity, info, plainText, messageReference, tasks[which]) }
            .setPositiveButton(R.string.dcmail_claude_send) { _, _ ->
                share(activity, info, plainText, messageReference, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun share(
        activity: Activity,
        info: MessageViewInfo,
        plainText: String?,
        messageReference: MessageReference,
        task: String,
    ) {
        val message = info.message
        val from = message?.from?.firstOrNull()?.toString().orEmpty()
        val date = message?.sentDate?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) }
        val subject = info.subject ?: message?.subject.orEmpty()

        val text = buildString {
            if (task.isNotEmpty()) append(task).append("\n\n")
            append(activity.getString(R.string.dcmail_claude_eml_note)).append("\n\n")
            append("--- E-Mail ---\n")
            if (from.isNotEmpty()) append("Von: ").append(from).append("\n")
            if (!date.isNullOrEmpty()) append("Datum: ").append(date).append("\n")
            if (subject.isNotEmpty()) append("Betreff: ").append(subject).append("\n")
            append("\n").append(plainText?.trim().orEmpty().take(MAX_TEXT_CHARS))
        }

        val progress = DcAiReply.showProgress(activity, R.string.dcmail_claude_preparing)
        Thread {
            val uris = ArrayList<Uri>()
            var error: String? = null
            try {
                // The complete mail incl. all attachments as .eml file
                val fileName = safeFileName(subject.ifBlank { "E-Mail" }) + ".eml"
                val rawUri = RawMessageProvider.getRawMessageUri(messageReference)
                uris.add(AttachmentTempFileProvider.createTempUriForContentUri(activity, rawUri, fileName))

                // PDFs and images additionally as separate files, so Claude can open them directly
                for (attachment in info.attachments.orEmpty()) {
                    val type = attachment.mimeType.orEmpty().lowercase()
                    if (attachment.inlineAttachment) continue
                    if (type != "application/pdf" && !type.startsWith("image/")) continue
                    val name = attachment.displayName?.takeIf { it.isNotBlank() } ?: "Anhang"
                    try {
                        uris.add(AttachmentTempFileProvider.createTempUriForContentUri(activity, attachment.internalUri, name))
                    } catch (e: java.io.IOException) {
                        // Skip attachments that can't be read; they are still inside the .eml
                    }
                }
            } catch (e: java.io.IOException) {
                error = e.message ?: e.javaClass.simpleName
            } catch (e: RuntimeException) {
                error = e.message ?: e.javaClass.simpleName
            }

            activity.runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error != null) {
                    Toast.makeText(activity, activity.getString(R.string.dcmail_claude_error, error), Toast.LENGTH_LONG)
                        .show()
                    return@runOnUiThread
                }
                startShare(activity, text, subject, uris)
            }
        }.start()
    }

    private fun startShare(activity: Activity, text: String, subject: String, uris: ArrayList<Uri>) {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
            intent.clipData = clip
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)

        val direct = Intent(intent).setPackage(CLAUDE_PACKAGE)
        try {
            if (direct.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(direct)
            } else {
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.dcmail_claude_title)))
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.dcmail_claude_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(MAX_FILE_NAME).ifBlank { "E-Mail" }
}
