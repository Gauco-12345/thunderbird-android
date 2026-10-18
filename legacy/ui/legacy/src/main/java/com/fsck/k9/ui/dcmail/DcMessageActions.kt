package com.fsck.k9.ui.dcmail

import android.content.Context

/**
 * DC Mail: the actions that can be shown in the icon bar below an opened message.
 *
 * The order of the entries is the order of the icons in the bar.
 */
enum class DcMessageAction(val key: String) {
    REPLY("reply"),
    REPLY_ALL("reply_all"),
    FORWARD("forward"),
    ARCHIVE("archive"),
    MOVE("move"),
    SPAM("spam"),
    UNREAD("unread"),
    FLAG("flag"),
    DELETE("delete"),
}

/**
 * DC Mail: remembers which actions the user wants in the icon bar.
 */
object DcMessageActions {
    private const val PREFERENCES_NAME = "dcmail_message_actions"
    private const val KEY_ACTIONS = "actions"

    val DEFAULT: Set<String> = setOf(
        DcMessageAction.REPLY.key,
        DcMessageAction.REPLY_ALL.key,
        DcMessageAction.FORWARD.key,
        DcMessageAction.ARCHIVE.key,
        DcMessageAction.SPAM.key,
        DcMessageAction.DELETE.key,
    )

    fun load(context: Context): Set<String> {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ACTIONS, null)
            ?.toSet()
            ?: DEFAULT
    }

    fun save(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ACTIONS, keys.toSet())
            .apply()
    }

    fun enabledActions(context: Context): List<DcMessageAction> {
        val keys = load(context)
        return DcMessageAction.entries.filter { it.key in keys }
    }
}
