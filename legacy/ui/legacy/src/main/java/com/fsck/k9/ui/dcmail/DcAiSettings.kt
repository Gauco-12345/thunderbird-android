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

object DcAiSettings {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    private const val PREFERENCES_NAME = "dcmail_ai"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_INSTRUCTIONS = "instructions"

    fun load(context: Context): DcAiConfig {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return DcAiConfig(
            apiKey = preferences.getString(KEY_API_KEY, null).orEmpty(),
            model = preferences.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            instructions = preferences.getString(KEY_INSTRUCTIONS, null).orEmpty(),
        )
    }

    fun save(context: Context, config: DcAiConfig) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_INSTRUCTIONS, config.instructions)
            .apply()
    }
}
