package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DC Mail: dialogs for creating and restoring a password-protected backup.
 */
object DcBackupUi {
    private const val PADDING_DP = 24

    fun suggestedFileName(): String =
        "DC-Mail-Sicherung-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".dcmail"

    /** Called with the file the user picked for a new backup. */
    fun createBackup(activity: Activity, uri: Uri) {
        val (layout, first) = passwordField(activity, R.string.dcmail_backup_password_hint)
        val second = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(R.string.dcmail_backup_password_repeat)
        }
        layout.addView(second)

        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_backup_create_title)
            .setMessage(R.string.dcmail_backup_password_message)
            .setView(layout)
            .setPositiveButton(R.string.dcmail_backup_create_action) { _, _ ->
                val password = first.text.toString()
                when {
                    password.length < DcBackup.MIN_PASSWORD_LENGTH ->
                        showMessage(activity, activity.getString(R.string.dcmail_backup_password_too_short))
                    password != second.text.toString() ->
                        showMessage(activity, activity.getString(R.string.dcmail_backup_password_mismatch))
                    else -> run(activity, R.string.dcmail_backup_creating) {
                        DcBackup.export(activity.applicationContext, uri, password.toCharArray())
                        activity.getString(R.string.dcmail_backup_created)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Called with the backup file the user picked for restoring. */
    fun restoreBackup(activity: Activity, uri: Uri) {
        val (layout, field) = passwordField(activity, R.string.dcmail_backup_password_hint)
        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_backup_restore_title)
            .setMessage(R.string.dcmail_backup_restore_message)
            .setView(layout)
            .setPositiveButton(R.string.dcmail_backup_restore_action) { _, _ ->
                val password = field.text.toString().toCharArray()
                run(activity, R.string.dcmail_backup_restoring) {
                    val result = DcBackup.restore(activity.applicationContext, uri, password)
                    activity.getString(
                        R.string.dcmail_backup_restored,
                        result.accountsRestored,
                        result.accountsSkipped,
                    ) + if (result.accountsFailed > 0) {
                        "\n\n" + activity.getString(R.string.dcmail_backup_restore_failed_accounts, result.accountsFailed)
                    } else {
                        ""
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun passwordField(activity: Activity, hintRes: Int): Pair<LinearLayout, EditText> {
        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(padding, padding / 3, padding, 0)
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(hintRes)
        }
        layout.addView(field)
        return layout to field
    }

    private fun run(activity: Activity, progressRes: Int, work: () -> String) {
        val progress = DcAiReply.showProgress(activity, progressRes)
        Thread {
            val message = try {
                work()
            } catch (e: DcBackup.WrongPasswordException) {
                activity.getString(R.string.dcmail_backup_wrong_password)
            } catch (e: DcBackup.InvalidFileException) {
                e.message.orEmpty()
            } catch (e: Exception) {
                activity.getString(R.string.dcmail_backup_error, e.message ?: e.javaClass.simpleName)
            }
            activity.runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (activity.isFinishing || activity.isDestroyed) {
                    Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                } else {
                    showMessage(activity, message)
                }
            }
        }.start()
    }

    private fun showMessage(activity: Activity, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_backup_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
