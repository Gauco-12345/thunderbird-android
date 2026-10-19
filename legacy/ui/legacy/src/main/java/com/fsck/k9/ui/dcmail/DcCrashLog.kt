package com.fsck.k9.ui.dcmail

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DC Mail: remembers the last crash on the device so it can be shown and copied from the settings.
 *
 * Only the technical stack trace is stored, locally in the app's private storage; nothing is sent anywhere.
 */
object DcCrashLog {
    private const val FILE_NAME = "dcmail_last_crash.txt"
    private const val MAX_LENGTH = 12_000

    @Volatile
    private var installed = false

    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (ignored: Throwable) {
                // Never let crash logging cause another crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    fun describe(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().take(MAX_LENGTH)
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val text = "DC Mail Build ${DcUpdater.currentBuildNumber(context)} – $time – Thread ${thread.name}\n\n" +
            describe(throwable)
        File(context.filesDir, FILE_NAME).writeText(text)
    }
}
