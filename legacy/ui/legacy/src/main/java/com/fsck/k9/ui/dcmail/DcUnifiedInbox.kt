package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.k9mail.legacy.di.DI
import com.fsck.k9.ui.R
import kotlinx.coroutines.runBlocking
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.mail.folder.api.data.repository.PartialUpdatableFolderDetails

/**
 * DC Mail: choose which accounts' inboxes are combined in the unified inbox.
 */
object DcUnifiedInbox {
    private data class Entry(val accountId: net.thunderbird.feature.account.AccountId, val folderId: Long, val label: String)

    fun show(activity: Activity) {
        Thread {
            val repository = DI.get(FolderDetailsRepository::class.java)
            val entries = mutableListOf<Entry>()
            val checked = mutableListOf<Boolean>()
            for (account in DI.get(LegacyAccountManager::class.java).findAll()) {
                val inboxId = account.inboxFolderId ?: continue
                val details = runBlocking {
                    repository.findById(account.id, inboxId).fold(onSuccess = { it }, onFailure = { null })
                } ?: continue
                val name = account.name?.takeIf { it.isNotBlank() }
                entries.add(Entry(account.id, inboxId, if (name != null) "$name (${account.email})" else account.email))
                checked.add(details.isIntegrate)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                showDialog(activity, entries, checked.toBooleanArray())
            }
        }.start()
    }

    private fun showDialog(activity: Activity, entries: List<Entry>, checked: BooleanArray) {
        if (entries.isEmpty()) {
            Toast.makeText(activity, R.string.dcmail_unified_none, Toast.LENGTH_SHORT).show()
            return
        }
        val original = checked.copyOf()
        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_unified_title)
            .setMultiChoiceItems(entries.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.dcmail_template_save) { _, _ ->
                val changes = entries.indices.filter { checked[it] != original[it] }
                if (changes.isEmpty()) return@setPositiveButton
                val appContext = activity.applicationContext
                Thread {
                    val repository = DI.get(FolderDetailsRepository::class.java)
                    runBlocking {
                        for (index in changes) {
                            val entry = entries[index]
                            repository.update(
                                entry.accountId,
                                PartialUpdatableFolderDetails(
                                    folderId = entry.folderId,
                                    includeInUnifiedInbox = checked[index],
                                ),
                            )
                        }
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(appContext, R.string.dcmail_unified_saved, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
