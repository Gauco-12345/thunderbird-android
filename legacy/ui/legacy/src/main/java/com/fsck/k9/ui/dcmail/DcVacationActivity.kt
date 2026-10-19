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
import app.k9mail.legacy.di.DI
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity
import java.io.IOException
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory

/**
 * DC Mail: out-of-office reply via ManageSieve. First stage: check whether the server's Sieve service is reachable.
 */
class DcVacationActivity : BaseActivity() {
    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DcCrashLog.install(this)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dcmail_vacation_title)

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let {
            try {
                AccountIdFactory.of(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val account = accountId?.let { DI.get(LegacyAccountManager::class.java).findById(it) }
        if (account == null) {
            val message = TextView(this)
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            message.setPadding(padding, padding, padding, padding)
            message.text = getString(R.string.dcmail_vacation_no_account)
            findViewById<FrameLayout>(R.id.content_container).addView(message)
            return
        }
        val server = account.incomingServerSettings

        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padding, padding, padding, padding)

        val intro = TextView(this)
        intro.setText(R.string.dcmail_vacation_intro)
        column.addView(intro)

        val hostInput = EditText(this)
        hostInput.setSingleLine(true)
        hostInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hostInput.setText(server.host)
        column.addView(label(R.string.dcmail_vacation_host, padding))
        column.addView(hostInput)

        val portInput = EditText(this)
        portInput.setSingleLine(true)
        portInput.inputType = InputType.TYPE_CLASS_NUMBER
        portInput.setText(DcManageSieve.DEFAULT_PORT.toString())
        column.addView(label(R.string.dcmail_vacation_port, padding))
        column.addView(portInput)

        val testButton = Button(this)
        testButton.setText(R.string.dcmail_vacation_test)
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        buttonParams.topMargin = padding
        buttonParams.gravity = Gravity.END
        column.addView(testButton, buttonParams)

        resultView = TextView(this)
        resultView.setPadding(0, padding, 0, 0)
        resultView.setTextIsSelectable(true)
        column.addView(resultView)

        testButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: DcManageSieve.DEFAULT_PORT
            val password = server.password
            if (password.isNullOrEmpty()) {
                resultView.setText(R.string.dcmail_vacation_no_password)
                return@setOnClickListener
            }
            testButton.isEnabled = false
            resultView.setText(R.string.dcmail_vacation_testing)
            Thread {
                val message = try {
                    runTest(host, port, server.username, password)
                } catch (e: Throwable) {
                    getString(R.string.dcmail_vacation_failed, DcCrashLog.describe(e))
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        testButton.isEnabled = true
                        resultView.text = message
                    }
                }
            }.start()
        }

        val scrollView = ScrollView(this)
        scrollView.isNestedScrollingEnabled = true
        scrollView.addView(column)
        findViewById<FrameLayout>(R.id.content_container).addView(
            scrollView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun runTest(host: String, port: Int, username: String, password: String): String {
        return try {
            DcManageSieve.connect(host, port, username, password).use { sieve ->
                val scripts = sieve.listScripts()
                val vacation = "vacation" in sieve.extensions
                buildString {
                    append(getString(R.string.dcmail_vacation_ok)).append("\n\n")
                    if (sieve.implementation.isNotBlank()) {
                        append(getString(R.string.dcmail_vacation_server, sieve.implementation)).append("\n")
                    }
                    append(
                        getString(
                            if (vacation) R.string.dcmail_vacation_supported else R.string.dcmail_vacation_unsupported,
                        ),
                    ).append("\n")
                    append(getString(R.string.dcmail_vacation_scripts)).append("\n")
                    if (scripts.isEmpty()) {
                        append("–")
                    } else {
                        scripts.forEach { script ->
                            append("• ").append(script.name)
                            if (script.active) append(" (").append(getString(R.string.dcmail_vacation_active)).append(")")
                            append("\n")
                        }
                    }
                }
            }
        } catch (e: DcManageSieve.SieveException) {
            getString(R.string.dcmail_vacation_failed, e.message.orEmpty())
        } catch (e: IOException) {
            getString(R.string.dcmail_vacation_failed, e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            getString(R.string.dcmail_vacation_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun label(textResId: Int, padding: Int): TextView {
        val view = TextView(this)
        view.setText(textResId)
        view.setPadding(0, padding, 0, 0)
        return view
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val EXTRA_ACCOUNT_ID = "accountId"
        private const val PADDING_DP = 16

        fun start(context: Context, accountId: String) {
            context.startActivity(
                Intent(context, DcVacationActivity::class.java).putExtra(EXTRA_ACCOUNT_ID, accountId),
            )
        }
    }
}
