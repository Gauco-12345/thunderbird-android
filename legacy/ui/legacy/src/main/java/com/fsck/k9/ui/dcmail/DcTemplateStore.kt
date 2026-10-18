package com.fsck.k9.ui.dcmail

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * DC Mail: a reusable text snippet that can be inserted while composing a message.
 */
data class DcTemplate(
    val name: String,
    val text: String,
)

/**
 * DC Mail: stores message templates on the device as a small JSON list.
 */
object DcTemplateStore {
    private const val PREFERENCES_NAME = "dcmail_templates"
    private const val KEY_TEMPLATES = "templates"
    private const val FIELD_NAME = "name"
    private const val FIELD_TEXT = "text"

    fun load(context: Context): List<DcTemplate> {
        val json = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATES, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                DcTemplate(
                    name = item.optString(FIELD_NAME),
                    text = item.optString(FIELD_TEXT),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun save(context: Context, templates: List<DcTemplate>) {
        val array = JSONArray()
        for (template in templates) {
            array.put(
                JSONObject()
                    .put(FIELD_NAME, template.name)
                    .put(FIELD_TEXT, template.text),
            )
        }

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEMPLATES, array.toString())
            .apply()
    }
}
