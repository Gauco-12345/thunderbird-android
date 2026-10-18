package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    data class DcBuild(val number: Long, val runId: Long, val pageUrl: String)

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
                Result.success(fetchLatestBuild())
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
        val message = activity.getString(R.string.dcmail_update_available, latest.number, current)

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

    private fun downloadAndInstall(activity: Activity, latest: DcBuild) {
        val token = getToken(activity)
        val target = File(activity.cacheDir, APK_FILE_NAME)
        val progress = DcAiReply.showProgress(activity, R.string.dcmail_update_downloading)
        val appContext = activity.applicationContext

        Thread {
            val error = try {
                downloadApk(latest.runId, token, target)
                null
            } catch (e: UpdateException) {
                e.message
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            } catch (e: JSONException) {
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
                    try {
                        install(appContext, target)
                    } catch (e: IOException) {
                        showError(activity, e.message)
                    } catch (e: RuntimeException) {
                        showError(activity, e.message)
                    }
                }
            }
        }.start()
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
        )
    }

    private fun downloadApk(runId: Long, token: String, target: File) {
        val artifacts = try {
            JSONObject(request("$API/actions/runs/$runId/artifacts", token)).getJSONArray("artifacts")
        } catch (e: JSONException) {
            throw UpdateException(e.message ?: "Ungültige Antwort von GitHub")
        }
        if (artifacts.length() == 0) throw UpdateException("Für diesen Build gibt es keine APK (mehr).")
        val downloadUrl = artifacts.getJSONObject(0).getString("archive_download_url")

        // GitHub answers with a redirect to a signed storage URL that must be fetched without the token
        val redirect = open(downloadUrl, token, followRedirects = false)
        val location = try {
            if (redirect.responseCode !in HTTP_REDIRECT_MIN until HTTP_ERROR_THRESHOLD) {
                throw UpdateException("GitHub: HTTP ${redirect.responseCode}. Ist der Token gültig?")
            }
            redirect.getHeaderField("Location") ?: throw UpdateException("GitHub: Download-Adresse fehlt")
        } finally {
            redirect.disconnect()
        }

        val download = open(location, null, followRedirects = true)
        try {
            if (download.responseCode >= HTTP_ERROR_THRESHOLD) {
                throw UpdateException("Download: HTTP ${download.responseCode}")
            }
            extractApk(download.inputStream, target)
        } finally {
            download.disconnect()
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
 * DC Mail: receives the result of the install session and shows the system confirmation screen.
 */
class DcUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    context,
                    context.getString(R.string.dcmail_update_install_failed, message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
