package com.fsck.k9.ui.dcmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity

/**
 * DC Mail: lists the message templates and lets the user add, edit and delete them.
 */
class DcTemplatesActivity : BaseActivity() {
    private lateinit var adapter: ArrayAdapter<String>
    private var templates: MutableList<DcTemplate> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dcmail_templates_title)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())

        val listView = ListView(this)
        listView.isNestedScrollingEnabled = true
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                editTemplate(null)
            } else {
                editTemplate(position - 1)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position > 0) {
                confirmDelete(position - 1)
            }
            true
        }

        val container = findViewById<FrameLayout>(R.id.content_container)
        container.addView(
            listView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun reload() {
        templates = DcTemplateStore.load(this).toMutableList()
        adapter.clear()
        adapter.add(getString(R.string.dcmail_template_add))
        adapter.addAll(templates.map { it.name })
        adapter.notifyDataSetChanged()
    }

    private fun editTemplate(index: Int?) {
        val existing = index?.let { templates[it] }
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()

        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.dcmail_template_name_hint)
        nameInput.setSingleLine(true)
        nameInput.setText(existing?.name.orEmpty())

        val textInput = EditText(this)
        textInput.hint = getString(R.string.dcmail_template_text_hint)
        textInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        textInput.minLines = TEXT_MIN_LINES
        textInput.gravity = Gravity.TOP or Gravity.START
        textInput.setText(existing?.text.orEmpty())

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(padding, padding / 2, padding, 0)
        layout.addView(nameInput)
        layout.addView(textInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.dcmail_template_new else R.string.dcmail_template_edit)
            .setView(layout)
            .setPositiveButton(R.string.dcmail_template_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                val text = textInput.text.toString()
                if (name.isNotEmpty() && text.isNotBlank()) {
                    val template = DcTemplate(name = name, text = text)
                    if (index == null) {
                        templates.add(template)
                    } else {
                        templates[index] = template
                    }
                    DcTemplateStore.save(this, templates)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dcmail_template_delete_confirm, templates[index].name))
            .setPositiveButton(R.string.dcmail_template_delete) { _, _ ->
                templates.removeAt(index)
                DcTemplateStore.save(this, templates)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PADDING_DP = 20
        private const val TEXT_MIN_LINES = 4

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DcTemplatesActivity::class.java))
        }
    }
}
