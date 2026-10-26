package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * DC Mail: the user's own prompts for "Send to Claude". Each prompt has a short name (shown in the list) and the
 * full text that is sent to Claude together with the mail.
 */
object DcClaudePrompts {
    data class Prompt(val name: String, val text: String)

    const val PREFERENCES_NAME = "dcmail_claude_prompts"
    private const val KEY_PROMPTS = "prompts"
    private const val PADDING_DP = 24
    private const val TEXT_MIN_LINES = 4

    fun load(context: Context): List<Prompt> {
        val raw = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY_PROMPTS, null)
            ?: return defaults(context)
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Prompt(item.optString("name"), item.optString("text"))
            }.filter { it.text.isNotBlank() }
        } catch (e: JSONException) {
            defaults(context)
        }
    }

    private fun save(context: Context, prompts: List<Prompt>) {
        val array = JSONArray()
        prompts.forEach { array.put(JSONObject().put("name", it.name).put("text", it.text)) }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROMPTS, array.toString()).apply()
    }

    private fun defaults(context: Context): List<Prompt> {
        val names = context.resources.getStringArray(R.array.dcmail_claude_prompt_names)
        val texts = context.resources.getStringArray(R.array.dcmail_claude_tasks)
        return names.indices.map { Prompt(names[it], texts.getOrElse(it) { names[it] }) }
    }

    /** List of all prompts: tap = edit, last entry = add a new one. */
    fun showManager(activity: Activity) {
        val prompts = load(activity).toMutableList()
        val labels = prompts.map { it.name.ifBlank { it.text.take(40) } } +
            activity.getString(R.string.dcmail_prompts_add)
        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_prompts_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == prompts.size) {
                    edit(activity, null) { prompt ->
                        prompts.add(prompt)
                        save(activity, prompts)
                        showManager(activity)
                    }
                } else {
                    edit(activity, prompts[which], onDelete = {
                        prompts.removeAt(which)
                        save(activity, prompts)
                        showManager(activity)
                    }) { prompt ->
                        prompts[which] = prompt
                        save(activity, prompts)
                        showManager(activity)
                    }
                }
            }
            .setNegativeButton(R.string.dcmail_prompts_done, null)
            .setNeutralButton(R.string.dcmail_prompts_reset) { _, _ ->
                AlertDialog.Builder(activity)
                    .setMessage(R.string.dcmail_prompts_reset_confirm)
                    .setPositiveButton(R.string.dcmail_prompts_reset) { _, _ ->
                        save(activity, defaults(activity))
                        showManager(activity)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> showManager(activity) }
                    .show()
            }
            .show()
    }

    private fun edit(
        activity: Activity,
        prompt: Prompt?,
        onDelete: (() -> Unit)? = null,
        onSave: (Prompt) -> Unit,
    ) {
        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(padding, padding / 3, padding, 0)

        val nameInput = EditText(activity)
        nameInput.setSingleLine(true)
        nameInput.hint = activity.getString(R.string.dcmail_prompts_name_hint)
        nameInput.setText(prompt?.name.orEmpty())
        layout.addView(nameInput)

        val textInput = EditText(activity)
        textInput.hint = activity.getString(R.string.dcmail_prompts_text_hint)
        textInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        textInput.minLines = TEXT_MIN_LINES
        textInput.gravity = Gravity.TOP or Gravity.START
        textInput.setText(prompt?.text.orEmpty())
        layout.addView(textInput)

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (prompt == null) R.string.dcmail_prompts_new else R.string.dcmail_prompts_edit)
            .setView(android.widget.ScrollView(activity).apply { addView(layout) })
            .setPositiveButton(R.string.dcmail_template_save) { _, _ ->
                val text = textInput.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(activity, R.string.dcmail_prompts_text_missing, Toast.LENGTH_SHORT).show()
                    showManager(activity)
                } else {
                    onSave(Prompt(nameInput.text.toString().trim().ifEmpty { text.take(40) }, text))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showManager(activity) }
        if (onDelete != null) {
            builder.setNeutralButton(R.string.dcmail_template_delete) { _, _ -> onDelete() }
        }
        builder.show()
    }
}
