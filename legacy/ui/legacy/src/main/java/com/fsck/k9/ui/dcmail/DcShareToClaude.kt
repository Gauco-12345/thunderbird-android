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
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream
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

        val progress = DcAiReply.showProgress(activity, R.string.dcmail_claude_preparing)
        Thread {
            val uris = ArrayList<Uri>()
            val skipped = mutableListOf<String>()
            var error: String? = null
            try {
                val dir = newShareDirectory(activity)

                // The mail itself as readable text file (headers + body); Claude can't open .eml files
                val mailText = buildString {
                    if (from.isNotEmpty()) append("Von: ").append(from).append("\n")
                    if (!date.isNullOrEmpty()) append("Datum: ").append(date).append("\n")
                    if (subject.isNotEmpty()) append("Betreff: ").append(subject).append("\n")
                    append("\n").append(plainText?.trim().orEmpty())
                }
                val mailFile = File(dir, safeFileName(subject.ifBlank { "E-Mail" }) + ".txt")
                mailFile.writeText(mailText, Charsets.UTF_8)
                uris.add(uriFor(activity, mailFile))

                for (attachment in info.attachments.orEmpty()) {
                    if (attachment.inlineAttachment) continue
                    val name = attachment.displayName?.takeIf { it.isNotBlank() } ?: "Anhang"
                    val extension = name.substringAfterLast('.', "").lowercase()
                    try {
                        when {
                            extension == "zip" || attachment.mimeType.orEmpty().lowercase().contains("zip") ->
                                unzip(activity, attachment.internalUri, dir, uris, skipped)
                            extension in SUPPORTED || attachment.mimeType.orEmpty().lowercase().startsWith("image/") ->
                                uris.add(copy(activity, attachment.internalUri, File(dir, safeFileName(name))))
                            else -> skipped.add(name)
                        }
                    } catch (e: java.io.IOException) {
                        skipped.add(name)
                    }
                }
            } catch (e: java.io.IOException) {
                error = e.message ?: e.javaClass.simpleName
            } catch (e: RuntimeException) {
                error = e.message ?: e.javaClass.simpleName
            }

            val text = buildString {
                if (task.isNotEmpty()) append(task).append("\n\n")
                append(activity.getString(R.string.dcmail_claude_files_note, subject.ifBlank { "E-Mail" }))
                if (skipped.isNotEmpty()) {
                    append("\n").append(activity.getString(R.string.dcmail_claude_skipped, skipped.joinToString(", ")))
                }
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

    /** File types the Claude app can read. Images are accepted by MIME type as well. */
    private val SUPPORTED = setOf(
        "pdf", "txt", "csv", "md", "json", "xml", "html", "htm", "docx", "xlsx", "pptx", "rtf",
        "png", "jpg", "jpeg", "gif", "webp",
    )
    private const val MAX_ZIP_ENTRIES = 20
    private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

    private fun newShareDirectory(activity: Activity): File {
        val dir = File(File(activity.cacheDir, "temp"), "claude-" + System.currentTimeMillis())
        if (!dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("Kein Zwischenspeicher")
        return dir
    }

    private fun uriFor(activity: Activity, file: File): Uri =
        FileProvider.getUriForFile(activity, activity.packageName + ".tempfileprovider", file)

    private fun copy(activity: Activity, source: Uri, target: File): Uri {
        val input = activity.contentResolver.openInputStream(source) ?: throw java.io.IOException("Anhang fehlt")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        return uriFor(activity, target)
    }

    /** Unpacks readable files from a ZIP attachment (flat, with size limits) so Claude can open them. */
    private fun unzip(activity: Activity, source: Uri, dir: File, uris: MutableList<Uri>, skipped: MutableList<String>) {
        val input = activity.contentResolver.openInputStream(source) ?: throw java.io.IOException("Anhang fehlt")
        var count = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Only the file name is used, never the path inside the archive
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val extension = name.substringAfterLast('.', "").lowercase()
                when {
                    entry.isDirectory || name.isBlank() || name.startsWith(".") -> Unit
                    extension !in SUPPORTED || count >= MAX_ZIP_ENTRIES -> skipped.add(name)
                    else -> {
                        val target = File(dir, safeFileName(name))
                        var written = 0L
                        target.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) break
                                output.write(buffer, 0, read)
                            }
                        }
                        if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            target.delete()
                            skipped.add(name)
                        } else {
                            uris.add(uriFor(activity, target))
                            count++
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
    }

    private const val BUFFER_SIZE = 64 * 1024

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(MAX_FILE_NAME).ifBlank { "E-Mail" }
}
