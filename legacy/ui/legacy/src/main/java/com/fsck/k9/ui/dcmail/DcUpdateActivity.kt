package com.fsck.k9.ui.dcmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity

/**
 * DC Mail: shows the installed build, stores the GitHub token and starts a manual update check.
 */
class DcUpdateActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dcmail_update_settings_title)

        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padding, padding, padding, padding)

        val buildInfo = TextView(this)
        buildInfo.text = getString(R.string.dcmail_update_current_build, DcUpdater.currentBuildNumber(this))
        column.addView(buildInfo)

        val autoCheck = CheckBox(this)
        autoCheck.setText(R.string.dcmail_update_auto_check)
        autoCheck.isChecked = DcUpdater.isAutoCheckEnabled(this)
        autoCheck.setOnCheckedChangeListener { _, isChecked -> DcUpdater.setAutoCheckEnabled(this, isChecked) }
        column.addView(autoCheck)

        val intro = TextView(this)
        intro.setText(R.string.dcmail_update_token_intro)
        intro.setPadding(0, padding, 0, 0)
        column.addView(intro)

        val tokenInput = EditText(this)
        tokenInput.hint = getString(R.string.dcmail_update_token_hint)
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        tokenInput.setSingleLine(true)
        tokenInput.setText(DcUpdater.getToken(this))
        column.addView(tokenInput)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.END
        buttons.setPadding(0, padding, 0, 0)

        val saveButton = Button(this)
        saveButton.setText(R.string.dcmail_template_save)
        saveButton.setOnClickListener {
            DcUpdater.setToken(this, tokenInput.text.toString())
            Toast.makeText(this, R.string.dcmail_ai_saved, Toast.LENGTH_SHORT).show()
        }
        buttons.addView(saveButton)

        val checkButton = Button(this)
        checkButton.setText(R.string.dcmail_update_check_now)
        checkButton.setOnClickListener {
            DcUpdater.setToken(this, tokenInput.text.toString())
            DcUpdater.check(this, manual = true)
        }
        buttons.addView(checkButton)

        column.addView(buttons)

        // DC Mail: last crash, so it can be copied and sent for analysis
        DcCrashLog.read(this)?.let { crash ->
            val crashTitle = TextView(this)
            crashTitle.setText(R.string.dcmail_crash_title)
            crashTitle.setPadding(0, padding * 2, 0, 0)
            crashTitle.setTypeface(crashTitle.typeface, android.graphics.Typeface.BOLD)
            column.addView(crashTitle)

            val crashText = TextView(this)
            crashText.text = crash
            crashText.setTextIsSelectable(true)
            crashText.textSize = CRASH_TEXT_SIZE_SP
            column.addView(crashText)

            val crashButtons = LinearLayout(this)
            crashButtons.orientation = LinearLayout.HORIZONTAL
            crashButtons.gravity = Gravity.END
            val copyButton = Button(this)
            copyButton.setText(R.string.dcmail_ai_copy)
            copyButton.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("DC Mail crash", crash))
                Toast.makeText(this, R.string.dcmail_crash_copied, Toast.LENGTH_SHORT).show()
            }
            crashButtons.addView(copyButton)
            val clearButton = Button(this)
            clearButton.setText(R.string.dcmail_template_delete)
            clearButton.setOnClickListener {
                DcCrashLog.clear(this)
                crashTitle.visibility = android.view.View.GONE
                crashText.visibility = android.view.View.GONE
                crashButtons.visibility = android.view.View.GONE
            }
            crashButtons.addView(clearButton)
            column.addView(crashButtons)
        }

        val scrollView = ScrollView(this)
        scrollView.isNestedScrollingEnabled = true
        scrollView.addView(column)

        findViewById<FrameLayout>(R.id.content_container).addView(
            scrollView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val PADDING_DP = 16
        private const val CRASH_TEXT_SIZE_SP = 11f

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DcUpdateActivity::class.java))
        }
    }
}
