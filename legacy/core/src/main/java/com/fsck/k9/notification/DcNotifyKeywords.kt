package com.fsck.k9.notification

import android.content.Context
import app.k9mail.legacy.di.DI

/**
 * DC Mail: per-account subject keywords for notifications.
 *
 * "Always" keywords notify even during quiet days/hours, "never" keywords suppress the notification completely.
 * Keywords are matched case-insensitively anywhere in the subject; they are stored comma or line separated.
 */
object DcNotifyKeywords {
    const val PREFERENCES_NAME = "dcmail_notify_keywords"
    private const val PREFIX_ALWAYS = "always_"
    private const val PREFIX_NEVER = "never_"

    enum class Kind { ALWAYS, NEVER }

    private fun key(kind: Kind, accountId: String) =
        (if (kind == Kind.ALWAYS) PREFIX_ALWAYS else PREFIX_NEVER) + accountId

    private fun context(): Context = DI.get(Context::class.java)

    fun load(context: Context, kind: Kind, accountId: String): String =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(key(kind, accountId), "").orEmpty()

    fun save(context: Context, kind: Kind, accountId: String, value: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(key(kind, accountId), value.trim()).apply()
    }

    fun parse(value: String): List<String> =
        value.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(kind: Kind, accountId: String, subject: String?): Boolean {
        if (subject.isNullOrBlank()) return false
        val words = try {
            parse(load(context(), kind, accountId))
        } catch (e: RuntimeException) {
            return false
        }
        if (words.isEmpty()) return false
        val lower = subject.lowercase()
        return words.any { lower.contains(it) }
    }
}
