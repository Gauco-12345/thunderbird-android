package com.fsck.k9.ui.dcmail

import android.content.Context

/**
 * DC Mail: settings for drafting replies with Claude.
 */
data class DcAiConfig(
    val apiKey: String,
    val model: String,
    val instructions: String,
)

/**
 * DC Mail: style choices for one reply. Each value is the index into the matching option list.
 */
data class DcAiStyle(
    val address: Int,
    val length: Int,
    val tone: Int,
    /** 0 = like the original mail, 1 = German, 2 = English */
    val language: Int = 0,
)

object DcAiSettings {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private const val PREFERENCES_NAME = "dcmail_ai"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_INSTRUCTIONS = "instructions"
    private const val KEY_STYLE_ADDRESS = "style_address"
    private const val KEY_STYLE_LENGTH = "style_length"
    private const val KEY_STYLE_TONE = "style_tone"
    private const val KEY_STYLE_LANGUAGE = "style_language"
    private const val DEFAULT_LENGTH = 1
    private const val DEFAULT_TONE = 1

    fun loadStyle(context: Context): DcAiStyle {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return DcAiStyle(
            address = preferences.getInt(KEY_STYLE_ADDRESS, 0),
            length = preferences.getInt(KEY_STYLE_LENGTH, DEFAULT_LENGTH),
            tone = preferences.getInt(KEY_STYLE_TONE, DEFAULT_TONE),
            language = preferences.getInt(KEY_STYLE_LANGUAGE, 0),
        )
    }

    fun saveStyle(context: Context, style: DcAiStyle) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STYLE_ADDRESS, style.address)
            .putInt(KEY_STYLE_LENGTH, style.length)
            .putInt(KEY_STYLE_TONE, style.tone)
            .putInt(KEY_STYLE_LANGUAGE, style.language)
            .apply()
    }

    fun load(context: Context): DcAiConfig {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return DcAiConfig(
            apiKey = DcSecretStore.read(context, PREFERENCES_NAME, KEY_API_KEY),
            model = preferences.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            instructions = preferences.getString(KEY_INSTRUCTIONS, null).orEmpty(),
        )
    }

    fun save(context: Context, config: DcAiConfig) {
        DcSecretStore.write(context, PREFERENCES_NAME, KEY_API_KEY, config.apiKey.trim())
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_INSTRUCTIONS, config.instructions)
            .apply()
    }
}
