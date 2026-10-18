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

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DcUpdateActivity::class.java))
        }
    }
}
