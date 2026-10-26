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
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.ui.R
import java.text.DateFormat

/**
 * DC Mail: hands the opened mail (text plus PDF/image attachments) to the Claude app together with a task.
 */
object DcShareToClaude {
    private const val CLAUDE_PACKAGE = "com.anthropic.claude"
    private const val MAX_TEXT_CHARS = 30_000
    private const val PADDING_DP = 24

    fun show(activity: Activity, info: MessageViewInfo, plainText: String?) {
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
            .setItems(tasks) { _, which -> share(activity, info, plainText, tasks[which]) }
            .setPositiveButton(R.string.dcmail_claude_send) { _, _ ->
                share(activity, info, plainText, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun share(activity: Activity, info: MessageViewInfo, plainText: String?, task: String) {
        val message = info.message
        val from = message?.from?.firstOrNull()?.toString().orEmpty()
        val date = message?.sentDate?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) }
        val subject = info.subject ?: message?.subject.orEmpty()

        val text = buildString {
            if (task.isNotEmpty()) append(task).append("\n\n")
            append("--- E-Mail ---\n")
            if (from.isNotEmpty()) append("Von: ").append(from).append("\n")
            if (!date.isNullOrEmpty()) append("Datum: ").append(date).append("\n")
            if (subject.isNotEmpty()) append("Betreff: ").append(subject).append("\n")
            append("\n").append(plainText?.trim().orEmpty().take(MAX_TEXT_CHARS))
        }

        val attachments: List<Uri> = info.attachments.orEmpty()
            .filter { it.isContentAvailable }
            .filter { attachment ->
                val type = attachment.mimeType.orEmpty().lowercase()
                type == "application/pdf" || type.startsWith("image/")
            }
            .map { it.internalUri }

        val intent = if (attachments.isEmpty()) {
            Intent(Intent.ACTION_SEND).setType("text/plain")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").also {
                it.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
                val clip = ClipData.newRawUri(null, attachments.first())
                attachments.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                it.clipData = clip
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
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
}
