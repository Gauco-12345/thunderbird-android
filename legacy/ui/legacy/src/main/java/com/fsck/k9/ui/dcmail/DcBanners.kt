package com.fsck.k9.ui.dcmail

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fsck.k9.ui.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * DC Mail: small hint boxes shown above an opened message (newsletter, phishing, invoice).
 */
object DcBanners {
    enum class Kind { INFO, WARNING }

    data class Action(val label: String, val onClick: () -> Unit)

    private const val PADDING_DP = 12
    private const val MARGIN_DP = 8
    private const val RADIUS_DP = 12

    fun create(context: Context, kind: Kind, title: String, text: String?, actions: List<Action>): LinearLayout {
        val density = context.resources.displayMetrics.density
        val padding = (PADDING_DP * density).toInt()

        val (backgroundAttr, foregroundAttr) = when (kind) {
            Kind.WARNING -> com.google.android.material.R.attr.colorErrorContainer to
                com.google.android.material.R.attr.colorOnErrorContainer
            Kind.INFO -> com.google.android.material.R.attr.colorSecondaryContainer to
                com.google.android.material.R.attr.colorOnSecondaryContainer
        }
        val background = MaterialColors.getColor(context, backgroundAttr, Color.LTGRAY)
        val foreground = MaterialColors.getColor(context, foregroundAttr, Color.BLACK)

        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(padding, padding, padding, padding / 2)
        box.background = GradientDrawable().apply {
            setColor(background)
            cornerRadius = RADIUS_DP * density
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (MARGIN_DP * density).toInt()
        params.setMargins(margin, margin / 2, margin, margin / 2)
        box.layoutParams = params

        val titleView = TextView(context)
        titleView.text = title
        titleView.setTextColor(foreground)
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        box.addView(titleView)

        if (!text.isNullOrBlank()) {
            val textView = TextView(context)
            textView.text = text
            textView.setTextColor(foreground)
            textView.setPadding(0, padding / 3, 0, 0)
            box.addView(textView)
        }

        if (actions.isNotEmpty()) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.END
            for (action in actions) {
                val button = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle)
                button.text = action.label
                button.setTextColor(foreground)
                button.setOnClickListener { action.onClick() }
                row.addView(button)
            }
            box.addView(row)
        } else {
            box.setPadding(padding, padding, padding, padding)
        }
        return box
    }

    /**
     * Asks for the address invoices are forwarded to (e.g. the Lexware import mailbox).
     */
    fun askInvoiceAddress(context: Context, onSaved: () -> Unit) {
        val input = EditText(context)
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        input.hint = context.getString(R.string.dcmail_invoice_address_hint)
        input.setText(DcMessageInsights.invoiceForwardAddress(context))

        val density = context.resources.displayMetrics.density
        val container = FrameLayout(context)
        val padding = (PADDING_DP * 2 * density).toInt()
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle(R.string.dcmail_invoice_address_title)
            .setMessage(R.string.dcmail_invoice_address_message)
            .setView(container)
            .setPositiveButton(R.string.dcmail_template_save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && !value.contains('@')) {
                    Toast.makeText(context, R.string.dcmail_invoice_address_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    DcMessageInsights.setInvoiceForwardAddress(context, value)
                    onSaved()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
