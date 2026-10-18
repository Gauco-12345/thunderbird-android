package com.fsck.k9.ui.dcmail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DC Mail: stores secrets (API keys, tokens) encrypted with a key that never leaves the Android Keystore.
 *
 * Values are saved as Base64(IV + ciphertext) in the app's private preferences. A plain-text value written by an
 * older DC Mail version is migrated on first read.
 */
object DcSecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dcmail_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val KEY_SIZE_BITS = 256
    private const val ENCRYPTED_SUFFIX = "_enc"

    fun read(context: Context, preferencesName: String, key: String): String {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

        val encrypted = preferences.getString(key + ENCRYPTED_SUFFIX, null)
        if (encrypted != null) {
            return decrypt(encrypted).orEmpty()
        }

        // Migrate a plain-text value from an older version
        val plain = preferences.getString(key, null)
        if (!plain.isNullOrEmpty()) {
            write(context, preferencesName, key, plain)
            return plain
        }

        return ""
    }

    fun write(context: Context, preferencesName: String, key: String, value: String) {
        val editor = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
        if (value.isEmpty()) {
            editor.remove(key + ENCRYPTED_SUFFIX)
        } else {
            val encrypted = encrypt(value)
            if (encrypted == null) {
                // Keystore unavailable: don't lose the value, but never keep it in plain text longer than needed
                editor.putString(key, value)
                editor.apply()
                return
            }
            editor.putString(key + ENCRYPTED_SUFFIX, encrypted)
        }
        editor.remove(key)
        editor.apply()
    }

    private fun encrypt(value: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: java.io.IOException) {
            null
        } catch (e: RuntimeException) {
            // e.g. ProviderException or IllegalArgumentException from a broken Keystore or bad Base64
            null
        }
    }

    private fun decrypt(value: String): String? {
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            if (bytes.size <= IV_LENGTH) return null
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: java.io.IOException) {
            null
        } catch (e: RuntimeException) {
            // e.g. ProviderException or IllegalArgumentException from a broken Keystore or bad Base64
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }
}
