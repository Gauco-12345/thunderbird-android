package com.fsck.k9.ui.dcmail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.k9mail.legacy.di.DI
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.LockableDatabase
import com.fsck.k9.ui.messagelist.MessageListItem
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager

/**
 * DC Mail: colour code for the message list.
 *
 * Red = warning (phishing signs or spam score from 5), orange = spam suspicion (score 3 to 5),
 * green = known contact (you wrote to them, or they are in your phone contacts), grey = newsletter.
 */
object DcMailCategories {
    enum class Category(val color: Int?) {
        WARNING(0xFFE24B4A.toInt()),
        SPAM_SUSPECT(0xFFEF9F27.toInt()),
        CONTACT(0xFF639922.toInt()),
        NEWSLETTER(0xFFB4B2A9.toInt()),
        NONE(null),
    }

    enum class Filter { ALL, CONTACTS, NEWSLETTER }

    private data class HeaderInfo(val newsletter: Boolean, val spamScore: Float?, val authFailed: Boolean)

    private const val PREFERENCES_NAME = "dcmail_categories"
    private const val KEY_ENABLED = "enabled"
    private const val WARNING_SCORE = 5f
    private const val SUSPECT_SCORE = 3f
    private const val CONTACTS_MAX_AGE_MS = 10L * 60 * 1000
    private const val MAX_SENT_ROWS = 5000
    private const val QUERY_CHUNK = 400

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val headerInfo = ConcurrentHashMap<String, HeaderInfo>()

    @Volatile
    private var knownContacts: Set<String> = emptySet()

    @Volatile
    private var contactsLoadedAt = 0L

    @Volatile
    var filter: Filter = Filter.ALL

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun key(item: MessageListItem) = item.account.id.toString() + ":" + item.databaseId

    private fun address(item: MessageListItem): String? = item.displayAddress?.address?.lowercase(Locale.ROOT)

    fun isKnownContact(item: MessageListItem): Boolean = address(item)?.let { it in knownContacts } == true

    fun isNewsletter(item: MessageListItem): Boolean = headerInfo[key(item)]?.newsletter == true

    fun category(item: MessageListItem): Category {
        val info = headerInfo[key(item)]
        val score = info?.spamScore
        val address = item.displayAddress
        return when {
            info?.authFailed == true -> Category.WARNING
            DcMessageInsights.brandMismatch(address?.personal, address?.address) -> Category.WARNING
            score != null && score >= WARNING_SCORE -> Category.WARNING
            score != null && score >= SUSPECT_SCORE -> Category.SPAM_SUSPECT
            isKnownContact(item) -> Category.CONTACT
            info?.newsletter == true -> Category.NEWSLETTER
            else -> Category.NONE
        }
    }

    fun matches(item: MessageListItem, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.CONTACTS -> category(item) == Category.CONTACT
        Filter.NEWSLETTER -> isNewsletter(item)
    }

    /**
     * Loads contact list and header details for [items] in the background; [onUpdated] runs on the main thread when
     * something new is known.
     */
    fun ensureLoaded(context: Context, items: List<MessageListItem>, onUpdated: () -> Unit) {
        if (!isEnabled(context)) return
        val appContext = context.applicationContext
        val missing = items.filter { !headerInfo.containsKey(key(it)) }
        val contactsStale = System.currentTimeMillis() - contactsLoadedAt > CONTACTS_MAX_AGE_MS
        if (missing.isEmpty() && !contactsStale) return

        executor.execute {
            var changed = false
            if (System.currentTimeMillis() - contactsLoadedAt > CONTACTS_MAX_AGE_MS) {
                val contacts = loadKnownContacts(appContext)
                contactsLoadedAt = System.currentTimeMillis()
                if (contacts != knownContacts) {
                    knownContacts = contacts
                    changed = true
                }
            }
            if (missing.isNotEmpty()) {
                loadHeaders(missing)
                changed = true
            }
            if (changed) mainHandler.post(onUpdated)
        }
    }

    // --- known contacts: everyone you wrote to, plus phone contacts ---

    private fun loadKnownContacts(context: Context): Set<String> {
        val result = HashSet<String>()
        try {
            val accounts = DI.get(LegacyAccountManager::class.java).findAll()
            val own = accounts.map { it.email.lowercase(Locale.ROOT) }.toSet()
            val provider = DI.get(LocalStoreProvider::class.java)
            for (account in accounts) {
                collectSentRecipients(provider, account, result)
            }
            result.removeAll(own)
        } catch (e: RuntimeException) {
            // Colour code is optional
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.contains('@') }
                            ?.let { result.add(it) }
                    }
                }
            } catch (e: RuntimeException) {
                // ignore
            }
        }
        return result
    }

    /**
     * Known = people you replied to (the \Answered flag is set by every mail program, also eM Client or Roundcube)
     * plus recipients of mails you sent (Sent folder or any mail with your own address as sender).
     */
    private fun collectSentRecipients(provider: LocalStoreProvider, account: LegacyAccount, result: MutableSet<String>) {
        val own = account.email.lowercase(Locale.ROOT)
        val sentFolderId = account.sentFolderId ?: -1L
        try {
            provider.getInstance(account.id).database.execute(
                false,
                LockableDatabase.DbCallback<Unit> { db: SQLiteDatabase ->
                    db.rawQuery(
                        "SELECT sender_list FROM messages WHERE deleted = 0 AND empty = 0 AND answered = 1 " +
                            "ORDER BY date DESC LIMIT $MAX_SENT_ROWS",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            addAll(cursor.getString(0), result)
                        }
                    }
                    db.rawQuery(
                        "SELECT to_list, cc_list FROM messages WHERE deleted = 0 AND empty = 0 AND " +
                            "(folder_id = ? OR sender_list LIKE ? ESCAPE '\\') ORDER BY date DESC LIMIT $MAX_SENT_ROWS",
                        arrayOf(sentFolderId.toString(), "%" + escapeLike(own) + "%"),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            addAll(cursor.getString(0), result)
                            addAll(cursor.getString(1), result)
                        }
                    }
                },
            )
        } catch (e: Exception) {
            // ignore this account
        }
    }

    private fun addAll(packed: String?, result: MutableSet<String>) {
        if (packed.isNullOrEmpty()) return
        for (address in Address.unpack(packed)) {
            address.address?.lowercase(Locale.ROOT)?.let { result.add(it) }
        }
    }

    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // --- headers: newsletter, spam score, authentication ---

    private val SPAM_STATUS_SCORE = Regex("""score=(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val SPAM_SCORE_HEADER = Regex("""^x-spam-score:\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    private fun loadHeaders(items: List<MessageListItem>) {
        val provider = DI.get(LocalStoreProvider::class.java)
        for ((accountId, accountItems) in items.groupBy { it.account.id }) {
            for (chunk in accountItems.chunked(QUERY_CHUNK)) {
                val found = HashMap<Long, String>()
                try {
                    provider.getInstance(accountId).database.execute(
                        false,
                        LockableDatabase.DbCallback<Unit> { db: SQLiteDatabase ->
                            val ids = chunk.joinToString(",") { it.databaseId.toString() }
                            db.rawQuery(
                                "SELECT m.id, p.header FROM messages m JOIN message_parts p " +
                                    "ON p.id = m.message_part_id WHERE m.id IN ($ids)",
                                null,
                            ).use { cursor ->
                                while (cursor.moveToNext()) {
                                    found[cursor.getLong(0)] = cursor.getString(1).orEmpty()
                                }
                            }
                        },
                    )
                } catch (e: Exception) {
                    // leave these messages without colour
                }
                for (item in chunk) {
                    headerInfo[key(item)] = parseHeaders(found[item.databaseId].orEmpty())
                }
            }
        }
    }

    private fun parseHeaders(raw: String): HeaderInfo {
        // Unfold continuation lines so every header is on one line
        val lines = raw.replace("\r\n", "\n").replace(Regex("\n[ \t]+"), " ").split("\n")
        var newsletter = false
        var score: Float? = null
        var authFailed = false
        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)
            when {
                lower.startsWith("list-unsubscribe:") -> newsletter = true
                lower.startsWith("x-spam-status:") ->
                    SPAM_STATUS_SCORE.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let { score = it }
                lower.startsWith("x-spam-score:") && score == null ->
                    SPAM_SCORE_HEADER.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let { score = it }
                lower.startsWith("x-spam-level:") && score == null -> {
                    val stars = line.substringAfter(':').count { it == '*' }
                    if (stars > 0) score = stars.toFloat()
                }
                lower.startsWith("authentication-results:") ->
                    if (Regex("""\bdmarc=fail\b""").containsMatchIn(lower) ||
                        Regex("""\bspf=fail\b""").containsMatchIn(lower)
                    ) {
                        authFailed = true
                    }
            }
        }
        return HeaderInfo(newsletter, score, authFailed)
    }
}
