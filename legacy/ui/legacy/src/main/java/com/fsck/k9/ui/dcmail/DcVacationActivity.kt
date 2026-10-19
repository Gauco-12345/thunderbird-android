package com.fsck.k9.ui.dcmail

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import app.k9mail.legacy.di.DI
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity
import java.util.Calendar
import java.util.Locale
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory

/**
 * DC Mail: out-of-office reply and sender block list, managed on the mail server via ManageSieve.
 */
class DcVacationActivity : BaseActivity() {
    private lateinit var account: LegacyAccount
    private lateinit var statusView: TextView
    private lateinit var form: LinearLayout
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var subjectInput: EditText
    private lateinit var textInput: EditText
    private lateinit var fromButton: Button
    private lateinit var untilButton: Button
    private lateinit var daysInput: EditText
    private lateinit var blockedList: LinearLayout
    private var fromDate = ""
    private var untilDate = ""
    private var padding = 0
    private var blocklistMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DcCrashLog.install(this)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        blocklistMode = intent.getBooleanExtra(EXTRA_BLOCKLIST, false)
        title = getString(if (blocklistMode) R.string.dcmail_blocklist_title else R.string.dcmail_vacation_title)
        padding = (PADDING_DP * resources.displayMetrics.density).toInt()

        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let {
            try {
                AccountIdFactory.of(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val found = accountId?.let { DI.get(LegacyAccountManager::class.java).findById(it) }
        if (found == null) {
            val message = TextView(this)
            message.setPadding(padding, padding, padding, padding)
            message.text = getString(R.string.dcmail_vacation_no_account)
            findViewById<FrameLayout>(R.id.content_container).addView(message)
            return
        }
        account = found

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padding, padding, padding, padding)

        statusView = TextView(this)
        statusView.setText(R.string.dcmail_vacation_loading)
        column.addView(statusView)

        form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.visibility = View.GONE
        if (blocklistMode) buildBlocklist(form) else buildForm(form)
        column.addView(form)

        buildServerSection(column)

        val scrollView = ScrollView(this)
        scrollView.isNestedScrollingEnabled = true
        scrollView.addView(column)
        findViewById<FrameLayout>(R.id.content_container).addView(
            scrollView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        reload()
    }

    private fun buildForm(form: LinearLayout) {
        enabledSwitch = SwitchCompat(this)
        enabledSwitch.setText(R.string.dcmail_vacation_enabled)
        enabledSwitch.setPadding(0, padding, 0, 0)
        form.addView(enabledSwitch)

        subjectInput = EditText(this)
        subjectInput.setSingleLine(true)
        subjectInput.hint = getString(R.string.dcmail_vacation_subject_hint)
        form.addView(label(R.string.dcmail_vacation_subject))
        form.addView(subjectInput)

        textInput = EditText(this)
        textInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        textInput.minLines = TEXT_MIN_LINES
        textInput.gravity = Gravity.TOP or Gravity.START
        textInput.hint = getString(R.string.dcmail_vacation_text_hint)
        form.addView(label(R.string.dcmail_vacation_text))
        form.addView(textInput)

        val dates = LinearLayout(this)
        dates.orientation = LinearLayout.HORIZONTAL
        fromButton = Button(this)
        untilButton = Button(this)
        fromButton.setOnClickListener { pickDate(fromDate) { fromDate = it; updateDateButtons() } }
        untilButton.setOnClickListener { pickDate(untilDate) { untilDate = it; updateDateButtons() } }
        fromButton.setOnLongClickListener { fromDate = ""; updateDateButtons(); true }
        untilButton.setOnLongClickListener { untilDate = ""; updateDateButtons(); true }
        dates.addView(fromButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dates.addView(untilButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(label(R.string.dcmail_vacation_period))
        form.addView(dates)
        val dateHint = TextView(this)
        dateHint.setText(R.string.dcmail_vacation_period_hint)
        form.addView(dateHint)

        daysInput = EditText(this)
        daysInput.setSingleLine(true)
        daysInput.inputType = InputType.TYPE_CLASS_NUMBER
        form.addView(label(R.string.dcmail_vacation_days))
        form.addView(daysInput)

        val saveButton = Button(this)
        saveButton.setText(R.string.dcmail_template_save)
        saveButton.setOnClickListener { save() }
        val saveParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        saveParams.gravity = Gravity.END
        saveParams.topMargin = padding
        form.addView(saveButton, saveParams)
    }

    private fun buildBlocklist(form: LinearLayout) {
        val blockedHint = TextView(this)
        blockedHint.setText(R.string.dcmail_blocklist_hint)
        blockedHint.setPadding(0, padding, 0, padding)
        form.addView(blockedHint)
        blockedList = LinearLayout(this)
        blockedList.orientation = LinearLayout.VERTICAL
        form.addView(blockedList)
    }

    private fun buildServerSection(column: LinearLayout) {
        val config = DcSieveRules.serverConfig(this, account)

        val title = label(R.string.dcmail_vacation_server_section)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setPadding(0, padding * 2, 0, 0)
        column.addView(title)

        val hostInput = EditText(this)
        hostInput.setSingleLine(true)
        hostInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hostInput.setText(config.host)
        column.addView(label(R.string.dcmail_vacation_host))
        column.addView(hostInput)

        val portInput = EditText(this)
        portInput.setSingleLine(true)
        portInput.inputType = InputType.TYPE_CLASS_NUMBER
        portInput.setText(config.port.toString())
        column.addView(label(R.string.dcmail_vacation_port))
        column.addView(portInput)

        val reconnect = Button(this)
        reconnect.setText(R.string.dcmail_vacation_test)
        reconnect.setOnClickListener {
            DcSieveRules.saveServerConfig(
                this,
                account,
                DcSieveRules.ServerConfig(
                    host = hostInput.text.toString(),
                    port = portInput.text.toString().toIntOrNull() ?: DcManageSieve.DEFAULT_PORT,
                ),
            )
            reload()
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.END
        column.addView(reconnect, params)
    }

    private fun reload() {
        statusView.setText(R.string.dcmail_vacation_loading)
        runInBackground({ DcSieveRules.load(this, account) }) { state ->
            val imported = state.importedRule
            if (!blocklistMode && imported != null) {
                statusView.text = getString(R.string.dcmail_vacation_imported, imported)
            } else {
                statusView.setText(R.string.dcmail_vacation_connected)
            }
            form.visibility = View.VISIBLE
            showState(state)
        }
    }

    private fun showState(state: DcSieveRules.State) {
        if (blocklistMode) {
            showBlocklist(state)
            return
        }
        val vacation = state.vacation
        enabledSwitch.isChecked = vacation.enabled
        subjectInput.setText(vacation.subject)
        textInput.setText(vacation.text)
        fromDate = vacation.fromDate
        untilDate = vacation.untilDate
        daysInput.setText(vacation.days.toString())
        updateDateButtons()
    }

    private fun showBlocklist(state: DcSieveRules.State) {
        blockedList.removeAllViews()
        if (state.blocked.isEmpty()) {
            val empty = TextView(this)
            empty.text = "–"
            blockedList.addView(empty)
        }
        for (address in state.blocked) {
            val row = TextView(this)
            row.text = getString(R.string.dcmail_blocklist_entry, address)
            row.setPadding(0, padding / 2, 0, padding / 2)
            row.setOnClickListener { confirmUnblock(address) }
            blockedList.addView(row)
        }
    }

    private fun save() {
        val text = textInput.text.toString().trim()
        if (enabledSwitch.isChecked && text.isEmpty()) {
            Toast.makeText(this, R.string.dcmail_vacation_text_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val vacation = DcSieveRules.Vacation(
            enabled = enabledSwitch.isChecked,
            subject = subjectInput.text.toString().trim(),
            text = text,
            fromDate = fromDate,
            untilDate = untilDate,
            days = daysInput.text.toString().toIntOrNull()?.coerceIn(1, MAX_DAYS) ?: DEFAULT_DAYS,
        )
        statusView.setText(R.string.dcmail_vacation_saving)
        runInBackground({ DcSieveRules.saveVacation(this, account, vacation) }) {
            Toast.makeText(this, R.string.dcmail_vacation_saved, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun confirmUnblock(address: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dcmail_blocklist_remove_confirm, address))
            .setPositiveButton(R.string.dcmail_template_delete) { _, _ ->
                runInBackground({ DcSieveRules.unblockSender(this, account, address) }) { reload() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDate(current: String, onPicked: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        parseDate(current)?.let { (year, month, day) -> calendar.set(year, month - 1, day) }
        DatePickerDialog(
            this,
            { _, year, month, day -> onPicked(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)) },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun parseDate(value: String): Triple<Int, Int, Int>? {
        val parts = value.split("-").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
    }

    private fun updateDateButtons() {
        fromButton.text = getString(R.string.dcmail_vacation_from, formatDate(fromDate))
        untilButton.text = getString(R.string.dcmail_vacation_until, formatDate(untilDate))
    }

    private fun formatDate(value: String): String {
        val (year, month, day) = parseDate(value) ?: return getString(R.string.dcmail_vacation_open)
        return String.format(Locale.GERMANY, "%02d.%02d.%04d", day, month, year)
    }

    private fun <T> runInBackground(work: () -> T, onSuccess: (T) -> Unit) {
        Thread {
            val result = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(onSuccess).onFailure { error ->
                    statusView.text = getString(
                        R.string.dcmail_vacation_failed,
                        if (error is DcManageSieve.SieveException || error is java.io.IOException) {
                            error.message ?: error.javaClass.simpleName
                        } else {
                            DcCrashLog.describe(error)
                        },
                    )
                }
            }
        }.start()
    }

    private fun label(textResId: Int): TextView {
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
        private const val EXTRA_BLOCKLIST = "blocklist"
        private const val PADDING_DP = 16
        private const val TEXT_MIN_LINES = 4
        private const val DEFAULT_DAYS = 3
        private const val MAX_DAYS = 30

        fun start(context: Context, accountId: String) {
            context.startActivity(
                Intent(context, DcVacationActivity::class.java).putExtra(EXTRA_ACCOUNT_ID, accountId),
            )
        }

        fun startBlocklist(context: Context, accountId: String) {
            context.startActivity(
                Intent(context, DcVacationActivity::class.java)
                    .putExtra(EXTRA_ACCOUNT_ID, accountId)
                    .putExtra(EXTRA_BLOCKLIST, true),
            )
        }
    }
}
