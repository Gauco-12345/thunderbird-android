package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R

/**
 * DC Mail: shows the list of templates while composing and inserts the chosen one at the cursor.
 */
object DcTemplatePicker {

    @JvmStatic
    fun show(activity: Activity, target: EditText) {
        val templates = DcTemplateStore.load(activity)

        if (templates.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dcmail_templates_title)
                .setMessage(R.string.dcmail_templates_empty)
                .setPositiveButton(R.string.dcmail_templates_manage) { _, _ ->
                    DcTemplatesActivity.start(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val names = Array<CharSequence>(templates.size) { index -> templates[index].name }

        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_template_insert)
            .setItems(names) { _, which ->
                insert(target, templates[which].text)
            }
            .setNeutralButton(R.string.dcmail_templates_manage) { _, _ ->
                DcTemplatesActivity.start(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun insert(target: EditText, text: String) {
        val editable = target.text
        val selectionStart = target.selectionStart.coerceIn(0, editable.length)
        val selectionEnd = target.selectionEnd.coerceIn(0, editable.length)
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)

        editable.replace(start, end, text)
        target.requestFocus()
        target.setSelection((start + text.length).coerceAtMost(editable.length))
    }
}
