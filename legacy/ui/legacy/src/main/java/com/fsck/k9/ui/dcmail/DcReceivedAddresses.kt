package com.fsck.k9.ui.dcmail

import android.database.sqlite.SQLiteDatabase
import app.k9mail.legacy.di.DI
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.LockableDatabase
import java.util.Locale
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException

/**
 * DC Mail: suggests senders of messages already stored on the device while typing a recipient.
 *
 * Must be called from a background thread.
 */
object DcReceivedAddresses {
    private const val MIN_QUERY_LENGTH = 2
    private const val MAX_ROWS_PER_ACCOUNT = 300

    @JvmStatic
    fun find(query: String?, limit: Int): List<Address> {
        val needle = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (needle.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()

        val accountManager = DI.get(LegacyAccountManager::class.java)
        val localStoreProvider = DI.get(LocalStoreProvider::class.java)

        val accounts = accountManager.findAll()
        val ownAddresses = accounts.map { it.email.lowercase(Locale.ROOT) }.toSet()
        val pattern = "%" + escapeLike(needle) + "%"
        val found = LinkedHashMap<String, Address>()

        for (account in accounts) {
            if (found.size >= limit) break

            try {
                val database = localStoreProvider.getInstance(account.id).database
                database.execute(
                    false,
                    LockableDatabase.DbCallback<Unit> { db: SQLiteDatabase ->
                        collect(db, pattern, needle, ownAddresses, found, limit)
                    },
                )
            } catch (e: MessagingException) {
                // Suggestions are optional, ignore accounts whose database can't be read
            } catch (e: RuntimeException) {
                // Same as above
            }
        }

        return found.values.toList()
    }

    @Suppress("LongParameterList")
    private fun collect(
        db: SQLiteDatabase,
        pattern: String,
        needle: String,
        ownAddresses: Set<String>,
        found: LinkedHashMap<String, Address>,
        limit: Int,
    ) {
        db.rawQuery(
            "SELECT sender_list FROM messages " +
                "WHERE deleted = 0 AND empty = 0 AND sender_list LIKE ? ESCAPE '\\' " +
                "ORDER BY date DESC LIMIT $MAX_ROWS_PER_ACCOUNT",
            arrayOf(pattern),
        ).use { cursor ->
            while (cursor.moveToNext() && found.size < limit) {
                for (address in Address.unpack(cursor.getString(0))) {
                    val email = address.address.lowercase(Locale.ROOT)
                    val name = address.personal?.lowercase(Locale.ROOT).orEmpty()
                    if (email in ownAddresses || email in found) continue
                    if (email.contains(needle) || name.contains(needle)) {
                        found[email] = address
                        if (found.size >= limit) break
                    }
                }
            }
        }
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
