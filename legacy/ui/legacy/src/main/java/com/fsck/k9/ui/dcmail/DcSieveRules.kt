package com.fsck.k9.ui.dcmail

import android.content.Context
import app.k9mail.legacy.di.DI
import com.fsck.k9.mailstore.LocalStoreProvider
import net.thunderbird.core.android.account.LegacyAccount
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * DC Mail: out-of-office reply and sender block list, stored as Roundcube-compatible rules ("# rule:[...]") in the
 * active Sieve script. All other rules in that script stay untouched. Before every change the previous script is saved
 * on the server as "dcmail-backup" (not active).
 *
 * All functions talk to the network and must be called from a background thread.
 */
object DcSieveRules {
    private const val RULE_VACATION = "DC Mail Abwesenheit"
    private const val RULE_BLOCKLIST = "DC Mail Sperrliste"
    private const val BACKUP_SCRIPT = "dcmail-backup"
    private const val NEW_SCRIPT = "dcmail"
    private const val META_PREFIX = "# dcmail:"
    private const val DEFAULT_DAYS = 3

    private const val PREFERENCES_NAME = "dcmail_sieve"

    data class Vacation(
        val enabled: Boolean = false,
        val subject: String = "",
        val text: String = "",
        val fromDate: String = "",
        val untilDate: String = "",
        val days: Int = DEFAULT_DAYS,
    )

    data class State(
        val vacation: Vacation,
        val blocked: List<String>,
        /** Name of an existing out-of-office rule (e.g. from Roundcube) that was read and is replaced on saving. */
        val importedRule: String? = null,
    )

    data class ServerConfig(val host: String, val port: Int)

    // --- server settings (per account, defaults to the incoming server) ---

    fun serverConfig(context: Context, account: LegacyAccount): ServerConfig {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val id = account.id.toString()
        return ServerConfig(
            host = preferences.getString("host_$id", null)?.takeIf { it.isNotBlank() }
                ?: account.incomingServerSettings.host,
            port = preferences.getInt("port_$id", DcManageSieve.DEFAULT_PORT),
        )
    }

    fun saveServerConfig(context: Context, account: LegacyAccount, config: ServerConfig) {
        val id = account.id.toString()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("host_$id", config.host.trim())
            .putInt("port_$id", config.port)
            .apply()
    }

    fun connect(context: Context, account: LegacyAccount): DcManageSieve {
        val server = account.incomingServerSettings
        val password = server.password
        if (password.isNullOrEmpty()) {
            throw DcManageSieve.SieveException("Für dieses Konto ist kein Passwort gespeichert.")
        }
        val config = serverConfig(context, account)
        return DcManageSieve.connect(config.host, config.port, server.username, password)
    }

    // --- public operations ---

    fun load(context: Context, account: LegacyAccount): State {
        connect(context, account).use { sieve ->
            val active = sieve.listScripts().firstOrNull { it.active } ?: return State(Vacation(), emptyList())
            return parseState(sieve.getScript(active.name))
        }
    }

    fun saveVacation(context: Context, account: LegacyAccount, vacation: Vacation) {
        update(context, account) { state -> state.copy(vacation = vacation) }
    }

    fun blockSender(context: Context, account: LegacyAccount, address: String) {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return
        update(context, account) { state ->
            if (normalized in state.blocked) state else state.copy(blocked = state.blocked + normalized)
        }
    }

    fun unblockSender(context: Context, account: LegacyAccount, address: String) {
        val normalized = address.trim().lowercase()
        update(context, account) { state -> state.copy(blocked = state.blocked.filter { it != normalized }) }
    }

    private fun update(context: Context, account: LegacyAccount, change: (State) -> State) {
        val spamFolder = spamFolderName(account)
        connect(context, account).use { sieve ->
            val active = sieve.listScripts().firstOrNull { it.active }
            val original = active?.let { sieve.getScript(it.name) }.orEmpty()

            if (active != null) {
                sieve.putScript(BACKUP_SCRIPT, original)
            }

            val newState = change(parseState(original))
            val updated = buildScript(original, newState, spamFolder)
            val name = active?.name ?: NEW_SCRIPT
            sieve.putScript(name, updated)
            if (active == null) {
                sieve.setActive(name)
            }
        }
    }

    // --- parsing ---

    internal fun parseState(script: String): State {
        var vacation: Vacation? = null
        var blocked = emptyList<String>()
        var imported: String? = null
        var importedVacation: Vacation? = null
        for (block in splitRules(script).rules) {
            val name = ruleName(block)
            if (importedVacation == null && name != RULE_VACATION && name != RULE_BLOCKLIST) {
                parseForeignVacation(block)?.let {
                    importedVacation = it
                    imported = name
                }
            }
            val meta = block.lines().firstOrNull { it.trim().startsWith(META_PREFIX) }
                ?.trim()?.removePrefix(META_PREFIX) ?: continue
            try {
                val json = JSONObject(meta)
                when (ruleName(block)) {
                    RULE_VACATION -> vacation = Vacation(
                        enabled = json.optBoolean("enabled"),
                        subject = json.optString("subject"),
                        text = json.optString("text"),
                        fromDate = json.optString("from"),
                        untilDate = json.optString("until"),
                        days = json.optInt("days", DEFAULT_DAYS),
                    )
                    RULE_BLOCKLIST -> {
                        val list = json.optJSONArray("blocked") ?: JSONArray()
                        blocked = (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
                    }
                }
            } catch (e: JSONException) {
                // Rule was edited elsewhere; it will be replaced on the next save
            }
        }
        return if (vacation != null) {
            State(vacation, blocked)
        } else {
            State(importedVacation ?: Vacation(), blocked, if (importedVacation != null) imported else null)
        }
    }

    private val VACATION_COMMAND = Regex("""(?s)\bvacation\b(.*?)(text:[ \t]*\r?\n(.*?)\r?\n\.\r?\n?|"((?:[^"\\]|\\.)*)")\s*;""")
    private val DAYS = Regex(""":days\s+(\d+)""")
    private val SUBJECT = Regex(""":subject\s+"((?:[^"\\]|\\.)*)"""")
    private val DATE_GE = Regex(""""ge"\s+"(?:date|iso8601)"\s+"(\d{4}-\d{2}-\d{2})""")
    private val DATE_LE = Regex(""""le"\s+"(?:date|iso8601)"\s+"(\d{4}-\d{2}-\d{2})""")

    /**
     * Reads an out-of-office rule written by another program (e.g. Roundcube). Only rules whose sole action is the
     * vacation reply are taken over, so that replacing them never removes anything else.
     */
    private fun parseForeignVacation(block: String): Vacation? {
        val body = block.lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
        val match = VACATION_COMMAND.find(body) ?: return null
        val rest = body.removeRange(match.range)
            .replace(Regex("""\bstop\s*;"""), "")
            .substringAfter("{", missingDelimiterValue = "")
            .replace("}", "")
        if (rest.isNotBlank()) return null

        val options = match.groupValues[1]
        val text = if (match.groupValues[3].isNotEmpty() || match.groupValues[2].startsWith("text:")) {
            match.groupValues[3].lines().joinToString("\n") { if (it.startsWith("..")) it.substring(1) else it }
        } else {
            unquote(match.groupValues[4])
        }
        val condition = body.substringBefore("{")
        return Vacation(
            enabled = !Regex("""\bif\s+false\b""").containsMatchIn(condition),
            subject = SUBJECT.find(options)?.groupValues?.get(1)?.let { unquote(it) }.orEmpty(),
            text = text.trim(),
            fromDate = DATE_GE.find(condition)?.groupValues?.get(1).orEmpty(),
            untilDate = DATE_LE.find(condition)?.groupValues?.get(1).orEmpty(),
            days = DAYS.find(options)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_DAYS,
        )
    }

    private fun unquote(value: String) = value.replace("\\\"", "\"").replace("\\\\", "\\")

    private data class SplitScript(val requires: Set<String>, val header: String, val rules: List<String>)

    /**
     * Splits a script into its require list, the text before the first rule and the individual rule blocks.
     */
    private fun splitRules(script: String): SplitScript {
        val requires = linkedSetOf<String>()
        val requireRegex = Regex("""require\s+(\[[^\]]*\]|"[^"]*")\s*;""")
        requireRegex.findAll(script).forEach { match ->
            Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).forEach { requires.add(it.groupValues[1]) }
        }
        val withoutRequires = script.replace(requireRegex, "")

        val lines = withoutRequires.replace("\r\n", "\n").split("\n")
        val header = StringBuilder()
        val rules = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in lines) {
            if (line.trimStart().startsWith("# rule:[")) {
                current?.let { rules.add(it.toString().trimEnd()) }
                current = StringBuilder()
            }
            (current ?: header).append(line).append("\n")
        }
        current?.let { rules.add(it.toString().trimEnd()) }
        return SplitScript(requires, header.toString().trim(), rules)
    }

    private fun ruleName(block: String): String =
        Regex("""# rule:\[(.*?)\]""").find(block)?.groupValues?.get(1).orEmpty()

    // --- generation ---

    private fun buildScript(original: String, state: State, spamFolder: String?): String {
        val split = splitRules(original)
        val otherRules = split.rules.filter {
            val name = ruleName(it)
            name != RULE_VACATION && name != RULE_BLOCKLIST && (state.importedRule == null || name != state.importedRule)
        }

        val ownRules = mutableListOf<String>()
        val requires = LinkedHashSet(split.requires)

        if (state.blocked.isNotEmpty() && spamFolder != null) {
            requires.add("fileinto")
            ownRules.add(blocklistRule(state.blocked, spamFolder))
        }
        val vacation = state.vacation
        if (vacation.enabled || vacation.text.isNotBlank()) {
            requires.add("vacation")
            if (vacation.fromDate.isNotBlank() || vacation.untilDate.isNotBlank()) {
                requires.add("date")
                requires.add("relational")
            }
            ownRules.add(vacationRule(vacation))
        }

        return buildString {
            if (requires.isNotEmpty()) {
                append("require [").append(requires.joinToString(",") { quote(it) }).append("];\n")
            }
            if (split.header.isNotBlank()) append(split.header).append("\n")
            // DC Mail rules come first so the block list and the out-of-office reply always apply
            ownRules.forEach { append(it).append("\n") }
            otherRules.forEach { append(it).append("\n") }
        }.replace("\n", "\r\n")
    }

    /**
     * Entries starting with "@" block a whole domain, all others the exact address.
     */
    private fun blocklistRule(blocked: List<String>, spamFolder: String): String {
        val meta = JSONObject().put("blocked", JSONArray(blocked)).toString()
        val addresses = blocked.filterNot { it.startsWith("@") }
        val domains = blocked.filter { it.startsWith("@") }.map { it.removePrefix("@") }
        val tests = mutableListOf<String>()
        if (addresses.isNotEmpty()) {
            tests.add("address :is \"from\" [${addresses.joinToString(",") { quote(it) }}]")
        }
        if (domains.isNotEmpty()) {
            tests.add("address :domain :is \"from\" [${domains.joinToString(",") { quote(it) }}]")
        }
        val test = if (tests.size == 1) tests[0] else "anyof (${tests.joinToString(", ")})"
        return buildString {
            append("# rule:[").append(RULE_BLOCKLIST).append("]\n")
            append(META_PREFIX).append(meta).append("\n")
            append("if ").append(test).append("\n{\n")
            append("\tfileinto ").append(quote(spamFolder)).append(";\n")
            append("\tstop;\n}")
        }
    }

    private fun vacationRule(vacation: Vacation): String {
        val meta = JSONObject()
            .put("enabled", vacation.enabled)
            .put("subject", vacation.subject)
            .put("text", vacation.text)
            .put("from", vacation.fromDate)
            .put("until", vacation.untilDate)
            .put("days", vacation.days)
            .toString()

        val conditions = mutableListOf<String>()
        if (vacation.fromDate.isNotBlank()) {
            conditions.add("currentdate :value \"ge\" \"date\" ${quote(vacation.fromDate)}")
        }
        if (vacation.untilDate.isNotBlank()) {
            conditions.add("currentdate :value \"le\" \"date\" ${quote(vacation.untilDate)}")
        }
        val active = when (conditions.size) {
            0 -> "true"
            1 -> conditions[0]
            else -> "allof (${conditions.joinToString(", ")})"
        }
        // Same notation Roundcube uses for a switched-off rule, so Roundcube also shows it as "Aus"
        val test = if (vacation.enabled) active else "false # $active"

        val subject = if (vacation.subject.isNotBlank()) " :subject ${quote(vacation.subject)}" else ""
        return buildString {
            append("# rule:[").append(RULE_VACATION).append("]\n")
            append(META_PREFIX).append(meta).append("\n")
            append("if ").append(test).append("\n{\n")
            append("\tvacation :days ").append(vacation.days.coerceIn(1, 30)).append(subject)
                .append(" ").append(quote(vacation.text)).append(";\n}")
        }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun spamFolderName(account: LegacyAccount): String? {
        val spamFolderId = account.spamFolderId ?: return null
        return try {
            val folder = DI.get(LocalStoreProvider::class.java).getInstance(account.id).getFolder(spamFolderId)
            folder.open()
            folder.serverId
        } catch (e: Exception) {
            null
        }
    }
}
