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
import com.fsck.k9.provider.AttachmentTempFileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
        val prompts = DcClaudePrompts.load(activity)
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
            .setItems(prompts.map { it.name }.toTypedArray()) { _, which ->
                share(activity, info, plainText, messageReference, prompts[which].text)
            }
            .setPositiveButton(R.string.dcmail_claude_send) { _, _ ->
                share(activity, info, plainText, messageReference, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.dcmail_prompts_title) { _, _ -> DcClaudePrompts.showManager(activity) }
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
            val files = ArrayList<Pair<File, String>>()
            val included = StringBuilder()
            val skipped = mutableListOf<String>()
            var error: String? = null
            try {
                val dir = newShareDirectory(activity)
                val total = longArrayOf(0)
                for (attachment in info.attachments.orEmpty()) {
                    if (attachment.inlineAttachment) continue
                    val name = attachment.displayName?.takeIf { it.isNotBlank() } ?: "Anhang"
                    try {
                        val bytes = activity.contentResolver.openInputStream(attachment.internalUri)?.use {
                            readLimited(it, total)
                        }
                        if (bytes == null) skipped.add(name) else handle(name, bytes, dir, files, included, skipped, total, 0)
                    } catch (e: java.io.IOException) {
                        skipped.add(name)
                    }
                }

                // The mail itself as text file; text from Word/Excel/text attachments is added to it,
                // because the Claude app reliably reads only text, PDF and image files
                val mailText = buildString {
                    // The Claude app ignores the share text when files are attached, so the task goes into the file
                    if (task.isNotEmpty()) append("AUFGABE FÜR CLAUDE:\n").append(task).append("\n\n=== E-Mail ===\n")
                    if (from.isNotEmpty()) append("Von: ").append(from).append("\n")
                    if (!date.isNullOrEmpty()) append("Datum: ").append(date).append("\n")
                    if (subject.isNotEmpty()) append("Betreff: ").append(subject).append("\n")
                    append("\n").append(plainText?.trim().orEmpty())
                    if (included.isNotEmpty()) append("\n\n").append(included)
                }
                val mailName = safeFileName(subject.ifBlank { "E-Mail" }) + ".txt"
                val mailFile = newFile(dir, mailName)
                mailFile.writeText(mailText, Charsets.UTF_8)
                files.add(0, mailFile to mailName)
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
                if (task.isNotEmpty()) {
                    // Also on the clipboard: paste it into the Claude chat field with a long press
                    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Claude-Prompt", task))
                    Toast.makeText(activity, R.string.dcmail_claude_prompt_copied, Toast.LENGTH_LONG).show()
                }
                startShare(activity, text, subject, files)
            }
        }.start()
    }

    /**
     * PDFs and images become own files; text, CSV, HTML, Word, Excel, PowerPoint and OpenDocument files are
     * converted to text and added to the mail text file; ZIP files are unpacked (one level). Everything else is listed
     * as not included.
     */
    @Suppress("LongParameterList")
    private fun handle(
        name: String,
        bytes: ByteArray,
        dir: File,
        files: MutableList<Pair<File, String>>,
        included: StringBuilder,
        skipped: MutableList<String>,
        total: LongArray,
        depth: Int,
    ) {
        val extension = name.substringAfterLast('.', "").lowercase()
        when (extension) {
            in FILE_TYPES -> {
                if (files.size >= MAX_FILES) {
                    skipped.add(name)
                    return
                }
                val target = newFile(dir, safeFileName(name))
                target.writeBytes(bytes)
                files.add(target to safeFileName(name))
            }
            in TEXT_TYPES -> addTextFile(name, decodeText(bytes, extension), dir, files, included, skipped)
            in OFFICE_TYPES -> {
                val text = officeText(bytes, extension)
                if (text.isBlank()) skipped.add(name) else addTextFile(name, text, dir, files, included, skipped)
            }
            "zip" -> if (depth > 0) skipped.add(name) else unzip(name, bytes, dir, files, included, skipped, total)
            else -> skipped.add(name)
        }
    }

    @Suppress("LongParameterList")
    private fun unzip(
        zipName: String,
        bytes: ByteArray,
        dir: File,
        files: MutableList<Pair<File, String>>,
        included: StringBuilder,
        skipped: MutableList<String>,
        total: LongArray,
    ) {
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Only the file name is used, never the path inside the archive
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (!entry.isDirectory && name.isNotBlank() && !name.startsWith(".") && !name.startsWith("__MACOSX")) {
                    if (count >= MAX_ZIP_ENTRIES) {
                        skipped.add("$zipName/$name")
                    } else {
                        count++
                        try {
                            handle(name, readLimited(zip, total), dir, files,
                                included, skipped, total, 1)
                        } catch (e: java.io.IOException) {
                            skipped.add(name)
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Text content (e.g. a CSV file) becomes its own .txt file with the original name, e.g. "umsatz.csv.txt", so the
     * Claude app accepts it and the structure of the data stays intact.
     */
    @Suppress("LongParameterList")
    private fun addTextFile(
        name: String,
        text: String,
        dir: File,
        files: MutableList<Pair<File, String>>,
        included: StringBuilder,
        skipped: MutableList<String>,
    ) {
        if (files.size >= MAX_FILES) {
            appendText(included, name, text)
            return
        }
        // CSV keeps its name and type (the Claude app reads CSV); everything else becomes a .txt file
        val safe = safeFileName(name)
        val fileName = if (safe.lowercase().endsWith(".csv") || safe.lowercase().endsWith(".txt")) safe else "$safe.txt"
        val target = newFile(dir, fileName)
        target.writeText(text, Charsets.UTF_8)
        files.add(target to fileName)
        if (text.isBlank()) skipped.add(name)
    }

    private fun appendText(target: StringBuilder, name: String, text: String) {
        target.append("=== Anhang: ").append(name).append(" ===\n")
        target.append(text.trim().take(MAX_ATTACHMENT_CHARS)).append("\n\n")
    }

    private fun decodeText(bytes: ByteArray, extension: String): String {
        val text = String(bytes, Charsets.UTF_8)
        return if (extension == "html" || extension == "htm" || extension == "xml") stripTags(text) else text
    }

    private fun stripTags(xml: String): String = xml
        .replace(Regex("(?s)<(script|style)[^>]*>.*?</(script|style)>"), " ")
        .replace(Regex("</(w:p|a:p|text:p|p|div|br|tr|row)>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(w:tc|c|td|th|table:table-cell)>", RegexOption.IGNORE_CASE), "\t")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")

    /** Word, Excel, PowerPoint and OpenDocument files are ZIP archives with XML inside; the text is taken from there. */
    private fun officeText(bytes: ByteArray, extension: String): String {
        val wanted: (String) -> Boolean = when (extension) {
            "docx" -> { name -> name == "word/document.xml" }
            "xlsx" -> { name -> name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/sheet") }
            "pptx" -> { name -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") }
            else -> { name -> name == "content.xml" }
        }
        val parts = sortedMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (wanted(entry.name)) parts[entry.name] = stripTags(String(zip.readBytes(), Charsets.UTF_8))
                    entry = zip.nextEntry
                }
            }
        } catch (e: java.io.IOException) {
            return ""
        }
        return parts.values.joinToString("\n\n").trim()
    }

    private fun readLimited(input: InputStream, total: LongArray): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            total[0] += read
            if (size > MAX_ENTRY_BYTES || total[0] > MAX_TOTAL_BYTES) throw java.io.IOException("zu groß")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Physical file with a unique name in the temp directory; the display name is passed separately. */
    private fun newFile(dir: File, displayName: String): File =
        File(dir, "claude-" + System.currentTimeMillis() + "-" + (counter++) + "-" + displayName.take(MAX_FILE_NAME))

    private var counter = 0

    private fun startShare(activity: Activity, text: String, subject: String, files: List<Pair<File, String>>) {
        val uris = ArrayList(files.map { (file, name) -> uriFor(activity, file, name) })
        val types = files.map { mimeType(it.second) }.distinct()
        val type = when {
            types.size == 1 -> types.first()
            types.all { it.startsWith("image/") } -> "image/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
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

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "csv" -> "text/csv"
        else -> "text/plain"
    }

    /** Sent as own files: the Claude app reads these reliably. */
    private val FILE_TYPES = setOf("pdf", "png", "jpg", "jpeg", "gif", "webp")
    /** Added as text to the mail text file. */
    private val TEXT_TYPES = setOf("txt", "csv", "md", "json", "xml", "html", "htm", "log", "ics", "vcf")
    private val OFFICE_TYPES = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
    private const val MAX_FILES = 20
    private const val MAX_ZIP_ENTRIES = 30
    private const val MAX_ATTACHMENT_CHARS = 100_000
    private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    private fun newShareDirectory(activity: Activity): File {
        val dir = File(activity.cacheDir, "temp")
        if (!dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("Kein Zwischenspeicher")
        return dir
    }

    /**
     * The receiving app sees [displayName] and the MIME type; without the type in the URI the provider answers
     * "null" and the Claude app refuses the attachment.
     */
    private fun uriFor(activity: Activity, file: File, displayName: String): Uri {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".tempfileprovider", file, displayName)
        return AttachmentTempFileProvider.getMimeTypeUri(uri, mimeType(displayName))
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(MAX_FILE_NAME).ifBlank { "E-Mail" }
}
