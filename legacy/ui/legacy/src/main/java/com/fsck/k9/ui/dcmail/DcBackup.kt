package com.fsck.k9.ui.dcmail

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.k9mail.legacy.di.DI
import com.fsck.k9.preferences.SettingsExporter
import com.fsck.k9.preferences.SettingsImporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * DC Mail: password-protected backup of everything needed to set up DC Mail again – accounts including passwords,
 * general settings, DC Mail settings (templates, action bar, AI, rules …) and tokens.
 *
 * The file is encrypted with AES-256-GCM; the key is derived from the backup password with PBKDF2-HMAC-SHA256.
 * Without the password the file is useless. All functions block and must run on a background thread.
 */
object DcBackup {
    class WrongPasswordException : Exception()
    class InvalidFileException(message: String) : Exception(message)

    data class RestoreResult(val accountsRestored: Int, val accountsSkipped: Int, val accountsFailed: Int)

    private const val FORMAT = "dcmail-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    const val MIN_PASSWORD_LENGTH = 8

    /** DC Mail preference files that are part of the backup. */
    private val PREFERENCE_FILES = listOf(
        "dcmail_ai",
        "dcmail_categories",
        "dcmail_favicons",
        "dcmail_insights",
        "dcmail_message_actions",
        "dcmail_notify_keywords",
        "dcmail_sieve",
        "dcmail_templates",
        "dcmail_update",
    )

    /** Secrets kept in the Android Keystore; exported in plain text inside the encrypted backup. */
    private val SECRETS = listOf(
        "dcmail_ai" to "api_key",
        "dcmail_update" to "github_token",
    )

    // --- export ---

    fun export(context: Context, uri: Uri, password: CharArray) {
        val payload = JSONObject()
            .put("created", System.currentTimeMillis())
            .put("k9", Base64.encodeToString(exportK9Settings(), Base64.NO_WRAP))
            .put("prefs", exportPreferences(context))
            .put("secrets", exportSecrets(context))
            .put("accounts", exportAccountExtras())

        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val file = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))

        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.toString().toByteArray()) }
            ?: throw InvalidFileException("Datei kann nicht geschrieben werden")
    }

    private fun exportK9Settings(): ByteArray {
        val accounts = DI.get(LegacyAccountManager::class.java).findAll().map { it.id.toString() }.toSet()
        val output = ByteArrayOutputStream()
        runBlocking {
            DI.get(SettingsExporter::class.java).exportPreferences(
                outputStream = output,
                includeGlobals = true,
                accountUuids = accounts,
                includePasswords = true,
            )
        }
        return output.toByteArray()
    }

    private fun exportPreferences(context: Context): JSONObject {
        val result = JSONObject()
        for (name in PREFERENCE_FILES) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                // Keystore-encrypted secrets can't be moved to another device; they are exported separately
                if (key.endsWith("_enc")) continue
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                values.put(key, entry)
            }
            result.put(name, values)
        }
        return result
    }

    private fun exportSecrets(context: Context): JSONObject {
        val result = JSONObject()
        for ((file, key) in SECRETS) {
            val value = DcSecretStore.read(context, file, key)
            if (value.isNotEmpty()) result.put("$file/$key", value)
        }
        return result
    }

    /** DC Mail account settings that the standard settings export does not know about. */
    private fun exportAccountExtras(): JSONObject {
        val result = JSONObject()
        for (account in DI.get(LegacyAccountManager::class.java).findAll()) {
            result.put(
                account.id.toString(),
                JSONObject()
                    .put("email", account.email)
                    .put("quietDays", JSONArray(account.dcQuietDays.toList()))
                    .put("quietTimeEnabled", account.isDcQuietTimeEnabled)
                    .put("quietTimeStarts", account.dcQuietTimeStarts)
                    .put("quietTimeEnds", account.dcQuietTimeEnds)
                    .put("notificationIcon", account.dcNotificationIcon),
            )
        }
        return result
    }

    // --- restore ---

    fun restore(context: Context, uri: Uri, password: CharArray): RestoreResult {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw InvalidFileException("Datei kann nicht gelesen werden")
        val file = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            throw InvalidFileException("Das ist keine DC-Mail-Sicherung")
        }
        if (file.optString("format") != FORMAT) throw InvalidFileException("Das ist keine DC-Mail-Sicherung")
        if (file.optInt("version") > VERSION) {
            throw InvalidFileException("Die Sicherung stammt aus einer neueren DC-Mail-Version")
        }

        val salt = Base64.decode(file.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(file.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(file.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt, file.optInt("iterations", ITERATIONS)),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val payload = try {
            JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }

        restorePreferences(context, payload.optJSONObject("prefs") ?: JSONObject())
        restoreSecrets(context, payload.optJSONObject("secrets") ?: JSONObject())
        val result = restoreK9Settings(Base64.decode(payload.getString("k9"), Base64.NO_WRAP))
        restoreAccountExtras(payload.optJSONObject("accounts") ?: JSONObject(), result.second)
        remapAccountKeys(context, result.second)
        return result.first
    }

    private fun restorePreferences(context: Context, prefs: JSONObject) {
        for (name in prefs.keys()) {
            if (name !in PREFERENCE_FILES) continue
            val values = prefs.getJSONObject(name)
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (key in values.keys()) {
                val entry = values.getJSONObject(key)
                when (entry.optString("t")) {
                    "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                    "i" -> editor.putInt(key, entry.getInt("v"))
                    "l" -> editor.putLong(key, entry.getLong("v"))
                    "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                    "s" -> editor.putString(key, entry.getString("v"))
                    "ss" -> {
                        val array = entry.getJSONArray("v")
                        editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
            }
            editor.apply()
        }
    }

    private fun restoreSecrets(context: Context, secrets: JSONObject) {
        for ((file, key) in SECRETS) {
            val value = secrets.optString("$file/$key")
            if (value.isNotEmpty()) DcSecretStore.write(context, file, key, value)
        }
    }

    /**
     * Imports accounts that don't exist on this device yet (same account ID). General settings are only imported on a
     * fresh installation, so restoring on the same phone never overwrites anything. Returns the result and a map of
     * original account ID → account ID on this device.
     */
    private fun restoreK9Settings(xml: ByteArray): Pair<RestoreResult, Map<String, String>> {
        val accountManager = DI.get(LegacyAccountManager::class.java)
        val existing = accountManager.findAll().map { it.id.toString() }.toSet()
        val importer = DI.get(SettingsImporter::class.java)
        val contents = importer.getImportStreamContents(ByteArrayInputStream(xml))
        val toImport = contents.accounts.map { it.uuid }.filter { it !in existing }
        val importGlobals = existing.isEmpty() && contents.globalSettings

        val mapping = HashMap<String, String>()
        existing.forEach { mapping[it] = it }

        if (toImport.isEmpty() && !importGlobals) {
            return RestoreResult(0, contents.accounts.size, 0) to mapping
        }
        val results = runBlocking { importer.importSettings(ByteArrayInputStream(xml), importGlobals, toImport) }
        for (pair in results.importedAccounts) {
            mapping[pair.original.uuid] = pair.imported.uuid
        }
        return RestoreResult(
            accountsRestored = results.importedAccounts.size,
            accountsSkipped = contents.accounts.size - toImport.size,
            accountsFailed = results.erroneousAccounts.size,
        ) to mapping
    }

    private fun restoreAccountExtras(extras: JSONObject, mapping: Map<String, String>) {
        val accountManager = DI.get(LegacyAccountManager::class.java)
        for (originalId in extras.keys()) {
            val targetId = mapping[originalId] ?: continue
            val values = extras.getJSONObject(originalId)
            val accountId = try {
                AccountIdFactory.of(targetId)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val days = values.optJSONArray("quietDays")
            try {
                accountManager.updateSync(accountId) { account ->
                    account.copy(
                        dcQuietDays = if (days == null) {
                            account.dcQuietDays
                        } else {
                            (0 until days.length()).map { days.getInt(it) }.toSet()
                        },
                        isDcQuietTimeEnabled = values.optBoolean("quietTimeEnabled", account.isDcQuietTimeEnabled),
                        dcQuietTimeStarts = values.optString("quietTimeStarts", account.dcQuietTimeStarts),
                        dcQuietTimeEnds = values.optString("quietTimeEnds", account.dcQuietTimeEnds),
                        dcNotificationIcon = values.optString("notificationIcon", account.dcNotificationIcon),
                    )
                }
            } catch (e: RuntimeException) {
                // Keep the account as imported
            }
        }
    }

    /** Per-account keyword lists are stored under the account ID, which changes when an account is re-created. */
    private fun remapAccountKeys(context: Context, mapping: Map<String, String>) {
        val preferences = context.getSharedPreferences("dcmail_notify_keywords", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        for ((original, target) in mapping) {
            if (original == target) continue
            for (prefix in listOf("always_", "never_")) {
                val value = preferences.getString(prefix + original, null) ?: continue
                editor.putString(prefix + target, value).remove(prefix + original)
            }
        }
        editor.apply()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
