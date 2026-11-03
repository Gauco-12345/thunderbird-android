package com.fsck.k9.ui.dcmail

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R
import java.util.Locale
import net.thunderbird.core.android.account.LegacyAccount

/**
 * DC Mail: asks whether a sender rule (trust or block) should apply to the exact address or to the whole domain, then
 * writes it to the server in the background.
 */
object DcSenderRulesUi {
    enum class Rule { TRUST, BLOCK }

    /**
     * Returns false when [address] is not usable. [onChosen] runs on the main thread right after the user picked an
     * entry (before the server answered).
     */
    fun choose(
        context: Context,
        account: LegacyAccount,
        address: String?,
        rule: Rule,
        onChosen: (String) -> Unit = {},
    ): Boolean {
        val normalized = address?.trim()?.lowercase(Locale.ROOT)
        if (normalized.isNullOrEmpty() || !normalized.contains('@')) return false
        val domain = normalized.substringAfterLast('@', "")
        val choices = mutableListOf(normalized)
        val labels = mutableListOf<CharSequence>(context.getString(R.string.dcmail_block_choice_address, normalized))
        if (domain.isNotEmpty()) {
            choices.add("@$domain")
            labels.add(context.getString(R.string.dcmail_block_choice_domain, domain))
        }
        val titleRes = if (rule == Rule.TRUST) R.string.dcmail_allow_sender else R.string.dcmail_block_sender
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which ->
                val entry = choices[which]
                run(context.applicationContext, account, entry, rule)
                onChosen(entry)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun run(appContext: Context, account: LegacyAccount, entry: String, rule: Rule) {
        Thread {
            val error = try {
                if (rule == Rule.TRUST) {
                    DcSieveRules.allowSender(appContext, account, entry)
                } else {
                    DcSieveRules.blockSender(appContext, account, entry)
                }
                null
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
            Handler(Looper.getMainLooper()).post {
                val text = when {
                    error != null -> appContext.getString(R.string.dcmail_block_sender_failed, error)
                    rule == Rule.TRUST -> appContext.getString(R.string.dcmail_allow_sender_done, entry)
                    else -> appContext.getString(R.string.dcmail_block_sender_done, entry)
                }
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
