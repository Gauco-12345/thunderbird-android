package com.fsck.k9.ui.dcmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity

/**
 * DC Mail: API key, model and personal instructions for replies drafted by Claude.
 */
class DcAiSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dcmail_ai_settings_title)

        val config = DcAiSettings.load(this)
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padding, padding, padding, padding)

        val intro = TextView(this)
        intro.setText(R.string.dcmail_ai_settings_intro)
        column.addView(intro)

        val apiKeyInput = EditText(this)
        apiKeyInput.hint = getString(R.string.dcmail_ai_api_key_hint)
        apiKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        apiKeyInput.setSingleLine(true)
        apiKeyInput.setText(config.apiKey)
        column.addView(label(R.string.dcmail_ai_api_key_label, padding))
        column.addView(apiKeyInput)

        val modelInput = EditText(this)
        modelInput.hint = DcAiSettings.DEFAULT_MODEL
        modelInput.setSingleLine(true)
        modelInput.setText(config.model)
        column.addView(label(R.string.dcmail_ai_model_label, padding))
        column.addView(modelInput)

        val instructionsInput = EditText(this)
        instructionsInput.hint = getString(R.string.dcmail_ai_instructions_hint)
        instructionsInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        instructionsInput.minLines = INSTRUCTIONS_MIN_LINES
        instructionsInput.gravity = Gravity.TOP or Gravity.START
        instructionsInput.setText(config.instructions)
        column.addView(label(R.string.dcmail_ai_instructions_label, padding))
        column.addView(instructionsInput)

        val saveButton = Button(this)
        saveButton.setText(R.string.dcmail_template_save)
        saveButton.setOnClickListener {
            DcAiSettings.save(
                this,
                DcAiConfig(
                    apiKey = apiKeyInput.text.toString(),
                    model = modelInput.text.toString(),
                    instructions = instructionsInput.text.toString(),
                ),
            )
            Toast.makeText(this, R.string.dcmail_ai_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        buttonParams.topMargin = padding
        buttonParams.gravity = Gravity.END
        column.addView(saveButton, buttonParams)

        val scrollView = ScrollView(this)
        scrollView.isNestedScrollingEnabled = true
        scrollView.addView(column)

        findViewById<FrameLayout>(R.id.content_container).addView(
            scrollView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun label(textResId: Int, padding: Int): TextView {
        val view = TextView(this)
        view.setText(textResId)
        view.setPadding(0, padding, 0, 0)
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
        return view
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val PADDING_DP = 16
        private const val INSTRUCTIONS_MIN_LINES = 4

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DcAiSettingsActivity::class.java))
        }
    }
}
