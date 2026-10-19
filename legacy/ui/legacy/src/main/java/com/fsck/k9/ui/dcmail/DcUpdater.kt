package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fsck.k9.ui.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * DC Mail: checks the GitHub fork for a newer build, downloads the APK and hands it to the system installer.
 *
 * The build number is encoded in the versionCode (1000 + GitHub run number), see app-thunderbird/build.gradle.kts.
 */
object DcUpdater {
    private const val REPOSITORY = "Gauco-12345/thunderbird-android"
    private const val WORKFLOW_FILE = "dcmail-build.yml"
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    private const val VERSION_CODE_OFFSET = 1000L

    private const val PREFERENCES_NAME = "dcmail_update"
    private const val KEY_TOKEN = "github_token"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED_BUILD = "skipped_build"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val HTTP_ERROR_THRESHOLD = 400
    private const val HTTP_REDIRECT_MIN = 300
    private const val APK_FILE_NAME = "dcmail-update.apk"
    private const val ZIP_FILE_NAME = "dcmail-update.zip"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val CHANNEL_ID = "dcmail_update"
    private const val NOTIFICATION_ID = 0x0DC0
    const val ACTION_INSTALL_AGAIN = "de.datacreate.dcmail.action.INSTALL_UPDATE"
    private const val CHANGELOG_PATH = "scripts/dcmail/changelog.json"

    data class DcBuild(val number: Long, val runId: Long, val pageUrl: String, val sha: String, val notes: String = "")

    class UpdateException(message: String) : Exception(message)

    fun getToken(context: Context): String = DcSecretStore.read(context, PREFERENCES_NAME, KEY_TOKEN)

    fun setToken(context: Context, token: String) {
        DcSecretStore.write(context, PREFERENCES_NAME, KEY_TOKEN, token.trim())
    }

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CHECK, false)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun currentBuildNumber(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return (versionCode - VERSION_CODE_OFFSET).coerceAtLeast(0)
    }

    /**
     * Called when the app is opened. Checks at most every few hours and stays silent unless there is an update.
     */
    @JvmStatic
    fun checkAutomatically(activity: Activity) {
        if (!isAutoCheckEnabled(activity)) return

        val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS) return
        preferences.edit().putLong(KEY_LAST_CHECK, now).apply()

        check(activity, manual = false)
    }

    /**
     * Checks for an update. With [manual] the result is always reported, otherwise only when there is a new build.
     */
    fun check(activity: Activity, manual: Boolean) {
        val appContext = activity.applicationContext
        Thread {
            val result = try {
                val latest = fetchLatestBuild()
                val installed = currentBuildNumber(appContext)
                Result.success(
                    if (latest != null && latest.number > installed) {
                        latest.copy(notes = fetchNotes(latest, installed))
                    } else {
                        latest
                    },
                )
            } catch (e: UpdateException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: JSONException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                result.onSuccess { latest ->
                    val current = currentBuildNumber(appContext)
                    val skipped = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .getLong(KEY_SKIPPED_BUILD, 0)
                    if (latest == null || latest.number <= current) {
                        if (manual) {
                            Toast.makeText(activity, R.string.dcmail_update_none, Toast.LENGTH_SHORT).show()
                        }
                    } else if (manual || latest.number != skipped) {
                        offerUpdate(activity, current, latest)
                    }
                }.onFailure { error ->
                    if (manual) {
                        showError(activity, error.message)
                    }
                }
            }
        }.start()
    }

    private fun offerUpdate(activity: Activity, current: Long, latest: DcBuild) {
        val hasToken = getToken(activity).isNotBlank()
        val headline = activity.getString(R.string.dcmail_update_available, latest.number, current)
        val message = if (latest.notes.isBlank()) {
            headline
        } else {
            headline + "\n\n" + activity.getString(R.string.dcmail_update_whats_new) + "\n" + latest.notes
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_update_title)
            .setMessage(
                if (hasToken) message else message + "\n\n" + activity.getString(R.string.dcmail_update_no_token),
            )
            .setNegativeButton(R.string.dcmail_update_later) { _, _ ->
                activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_SKIPPED_BUILD, latest.number).apply()
            }

        if (hasToken) {
            builder.setPositiveButton(R.string.dcmail_update_install) { _, _ -> downloadAndInstall(activity, latest) }
            builder.setNeutralButton(R.string.dcmail_update_open_browser) { _, _ -> openInBrowser(activity, latest) }
        } else {
            builder.setPositiveButton(R.string.dcmail_update_open_browser) { _, _ -> openInBrowser(activity, latest) }
        }

        builder.show()
    }

    private fun openInBrowser(activity: Activity, latest: DcBuild) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl)))
        } catch (e: android.content.ActivityNotFoundException) {
            showError(activity, e.message)
        }
    }

    /**
     * Resolves the signed download address and hands the download to the system DownloadManager. It keeps running
     * when the screen locks or DC Mail is closed; [DcUpdateDownloadReceiver] installs the APK when it is complete.
     */
    private fun downloadAndInstall(activity: Activity, latest: DcBuild) {
        val token = getToken(activity)
        val progress = DcAiReply.showProgress(activity, R.string.dcmail_update_downloading)
        val appContext = activity.applicationContext

        Thread {
            val error = try {
                val location = resolveDownloadUrl(latest.runId, token)
                enqueueDownload(appContext, location, latest.number)
                null
            } catch (e: UpdateException) {
                e.message
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            } catch (e: RuntimeException) {
                e.message ?: e.javaClass.simpleName
            }

            activity.runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error != null) {
                    showError(activity, error)
                } else {
                    Toast.makeText(activity, R.string.dcmail_update_download_started, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun enqueueDownload(context: Context, url: String, buildNumber: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val previous = preferences.getLong(KEY_DOWNLOAD_ID, -1)
        if (previous >= 0) manager.remove(previous)

        val target = File(context.getExternalFilesDir(null), ZIP_FILE_NAME)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(R.string.dcmail_update_download_title, buildNumber))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = manager.enqueue(request)
        preferences.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
    }

    /**
     * Called by [DcUpdateDownloadReceiver] on a background thread when a download has finished.
     */
    internal fun onDownloadComplete(context: Context, downloadId: Long) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (downloadId != preferences.getLong(KEY_DOWNLOAD_ID, -1)) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = DownloadManager.STATUS_FAILED
        var reason = 0
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            }
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            notifyProblem(context, context.getString(R.string.dcmail_update_download_failed, reason))
            return
        }

        val zip = File(context.getExternalFilesDir(null), ZIP_FILE_NAME)
        val apk = File(context.cacheDir, APK_FILE_NAME)
        try {
            zip.inputStream().use { extractApk(it, apk) }
            zip.delete()
            manager.remove(downloadId)
            preferences.edit().remove(KEY_DOWNLOAD_ID).apply()
            install(context, apk)
        } catch (e: IOException) {
            notifyProblem(context, e.message ?: e.javaClass.simpleName)
        } catch (e: UpdateException) {
            notifyProblem(context, e.message ?: "")
        } catch (e: RuntimeException) {
            notifyProblem(context, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Installs the already downloaded APK again, e.g. after the confirmation screen was closed.
     */
    internal fun installAgain(context: Context) {
        val apk = File(context.cacheDir, APK_FILE_NAME)
        if (!apk.exists()) {
            notifyProblem(context, context.getString(R.string.dcmail_update_apk_missing))
            return
        }
        try {
            install(context, apk)
        } catch (e: IOException) {
            notifyProblem(context, e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            notifyProblem(context, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.dcmail_update_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }
    }

    private fun immutableFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    /**
     * Shows "tap to install" – needed because Android does not allow opening the install screen from the background.
     */
    internal fun notifyReadyToInstall(context: Context, confirm: Intent) {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, confirm, immutableFlags())
        show(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.dcmail_update_ready_title))
                .setContentText(context.getString(R.string.dcmail_update_ready_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    /**
     * Shows an error; when an APK is already downloaded, tapping the notification tries the installation again.
     */
    internal fun notifyProblem(context: Context, message: String) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.dcmail_update_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
        if (File(context.cacheDir, APK_FILE_NAME).exists()) {
            val retry = Intent(context, DcUpdateDownloadReceiver::class.java).setAction(ACTION_INSTALL_AGAIN)
            builder.setContentIntent(PendingIntent.getBroadcast(context, NOTIFICATION_ID, retry, immutableFlags()))
            builder.setContentText(message + " – " + context.getString(R.string.dcmail_update_tap_retry))
        }
        show(context, builder.build())
    }

    internal fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun show(context: Context, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed; nothing else we can do in the background
        }
    }

    private fun fetchLatestBuild(): DcBuild? {
        val json = JSONObject(
            request("$API/actions/workflows/$WORKFLOW_FILE/runs?branch=main&status=success&per_page=1", null),
        )
        val runs = json.optJSONArray("workflow_runs") ?: return null
        if (runs.length() == 0) return null
        val run = runs.getJSONObject(0)
        return DcBuild(
            number = run.getLong("run_number"),
            runId = run.getLong("id"),
            pageUrl = run.optString("html_url", "https://github.com/$REPOSITORY/actions"),
            sha = run.optString("head_sha"),
        )
    }

    /**
     * Reads scripts/dcmail/changelog.json of the given build and returns the notes of all builds newer than [current].
     */
    private fun fetchNotes(build: DcBuild, current: Long): String {
        if (build.sha.isBlank()) return ""
        return try {
            val raw = request("https://raw.githubusercontent.com/$REPOSITORY/${build.sha}/$CHANGELOG_PATH", null)
            val releases = JSONObject(raw).getJSONArray("releases")
            val text = StringBuilder()
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                val number = if (release.optString("build") == "next") build.number else release.optLong("build")
                if (number <= current || number > build.number) continue
                val notes = release.getJSONArray("notes")
                for (noteIndex in 0 until notes.length()) {
                    text.append("• ").append(notes.getJSONObject(noteIndex).optString("text")).append("\n")
                }
            }
            text.toString().trim()
        } catch (e: UpdateException) {
            ""
        } catch (e: IOException) {
            ""
        } catch (e: JSONException) {
            ""
        }
    }

    private fun resolveDownloadUrl(runId: Long, token: String): String {
        val artifacts = try {
            JSONObject(request("$API/actions/runs/$runId/artifacts", token)).getJSONArray("artifacts")
        } catch (e: JSONException) {
            throw UpdateException(e.message ?: "Ungültige Antwort von GitHub")
        }
        if (artifacts.length() == 0) throw UpdateException("Für diesen Build gibt es keine APK (mehr).")
        val downloadUrl = artifacts.getJSONObject(0).getString("archive_download_url")

        // GitHub answers with a redirect to a signed storage URL that must be fetched without the token
        val redirect = open(downloadUrl, token, followRedirects = false)
        try {
            if (redirect.responseCode !in HTTP_REDIRECT_MIN until HTTP_ERROR_THRESHOLD) {
                throw UpdateException("GitHub: HTTP ${redirect.responseCode}. Ist der Token gültig?")
            }
            return redirect.getHeaderField("Location") ?: throw UpdateException("GitHub: Download-Adresse fehlt")
        } finally {
            redirect.disconnect()
        }
    }

    private fun extractApk(stream: InputStream, target: File) {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    target.outputStream().use { zip.copyTo(it) }
                    return
                }
                entry = zip.nextEntry
            }
        }
        throw UpdateException("In der heruntergeladenen Datei ist keine APK.")
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Lets Android skip the confirmation when DC Mail updates itself (if the system allows it)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_FILE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, DcUpdateInstallReceiver::class.java),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun request(url: String, token: String?): String {
        val connection = open(url, token, followRedirects = true)
        try {
            val code = connection.responseCode
            val stream = if (code >= HTTP_ERROR_THRESHOLD) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code >= HTTP_ERROR_THRESHOLD) {
                throw UpdateException("GitHub: HTTP $code")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, token: String?, followRedirects: Boolean): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = followRedirects
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "DC-Mail")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        return connection
    }

    private fun showError(activity: Activity, message: String?) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_update_error_title)
            .setMessage(message ?: activity.getString(R.string.dcmail_update_error_title))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

/**
 * DC Mail: receives the result of the install session. Shows the system confirmation screen (or, when Android does not
 * allow that from the background, a "tap to install" notification) and reports failures as a notification.
 */
class DcUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                DcUpdater.notifyReadyToInstall(context, confirm)
                try {
                    context.startActivity(confirm)
                } catch (e: RuntimeException) {
                    // Not allowed from the background – the notification is still there
                }
            }

            PackageInstaller.STATUS_SUCCESS -> DcUpdater.cancelNotification(context)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val text = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    context.getString(R.string.dcmail_update_aborted)
                } else {
                    context.getString(R.string.dcmail_update_install_failed, message)
                }
                DcUpdater.notifyProblem(context, text)
            }
        }
    }
}

/**
 * DC Mail: reacts to the finished system download and to "tap to try again".
 */
class DcUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        DcUpdater.onDownloadComplete(appContext, id)
                    }
                    DcUpdater.ACTION_INSTALL_AGAIN -> DcUpdater.installAgain(appContext)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
