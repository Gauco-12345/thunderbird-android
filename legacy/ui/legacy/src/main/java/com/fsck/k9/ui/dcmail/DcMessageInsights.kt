package com.fsck.k9.ui.dcmail

import android.content.Context
import android.net.Uri
import com.fsck.k9.mail.Address
import com.fsck.k9.ui.R
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.MessageViewInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DC Mail: looks at an opened message and finds newsletter unsubscribe options, phishing signs and invoices.
 */
object DcMessageInsights {
    private const val PREFERENCES_NAME = "dcmail_insights"
    private const val KEY_INVOICE_FORWARD = "invoice_forward"
    private const val KEY_PHISHING = "phishing_warning"
    private const val PREFIX_UNSUBSCRIBED = "unsubscribed_"
    private const val REAPPEAR_GRACE_MS = 2L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 15_000
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    // --- settings ---

    fun invoiceForwardAddress(context: Context): String =
        prefs(context).getString(KEY_INVOICE_FORWARD, "").orEmpty()

    fun setInvoiceForwardAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_INVOICE_FORWARD, address.trim()).apply()
    }

    fun isPhishingWarningEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PHISHING, true)

    fun setPhishingWarningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PHISHING, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // --- newsletter / unsubscribe ---

    data class Unsubscribe(
        /** HTTPS address that supports RFC 8058 one-click unsubscribe (no browser needed). */
        val oneClick: Uri?,
        val mailto: Uri?,
        val https: Uri?,
    )

    fun unsubscribe(message: Message): Unsubscribe? {
        val header = message.getHeader("List-Unsubscribe").joinToString(",")
        if (header.isBlank()) return null
        val uris = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
        val mailto = uris.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.let { Uri.parse(it) }
        val https = uris.firstOrNull { it.startsWith("https://", ignoreCase = true) }?.let { Uri.parse(it) }
        if (mailto == null && https == null) return null
        val oneClickHeader = message.getHeader("List-Unsubscribe-Post").joinToString(",")
        val oneClick = if (https != null && oneClickHeader.contains("One-Click", ignoreCase = true)) https else null
        return Unsubscribe(oneClick, mailto, https)
    }

    /**
     * RFC 8058 one-click unsubscribe. Must be called from a background thread. Returns true on success.
     */
    fun unsubscribeOneClick(uri: Uri): Boolean {
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "DC-Mail")
            connection.outputStream.use { it.write("List-Unsubscribe=One-Click".toByteArray(Charsets.US_ASCII)) }
            connection.responseCode in HTTP_OK_MIN..HTTP_OK_MAX
        } catch (e: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun rememberUnsubscribed(context: Context, sender: String) {
        prefs(context).edit().putLong(PREFIX_UNSUBSCRIBED + sender.lowercase(), System.currentTimeMillis()).apply()
    }

    /**
     * Returns when the user unsubscribed from [sender] if this message arrived clearly after that, otherwise null.
     */
    fun unsubscribedButStillSending(context: Context, sender: String, messageDate: Long): Long? {
        val time = prefs(context).getLong(PREFIX_UNSUBSCRIBED + sender.lowercase(), 0)
        return if (time > 0 && messageDate > time + REAPPEAR_GRACE_MS) time else null
    }

    // --- phishing ---

    /** Brand name in the display name → domain parts that legitimately belong to it. */
    private val BRANDS: Map<String, List<String>> = mapOf(
        "n26" to listOf("n26"),
        "paypal" to listOf("paypal"),
        "sparkasse" to listOf("sparkasse", "s-"),
        "volksbank" to listOf("volksbank", "vr", "atruvia"),
        "raiffeisen" to listOf("raiffeisen", "vr", "atruvia"),
        "deutsche bank" to listOf("db.com", "deutsche-bank", "deutschebank"),
        "commerzbank" to listOf("commerzbank"),
        "comdirect" to listOf("comdirect"),
        "postbank" to listOf("postbank"),
        "dkb" to listOf("dkb"),
        "ing-diba" to listOf("ing"),
        "consorsbank" to listOf("consorsbank"),
        "amazon" to listOf("amazon"),
        "dhl" to listOf("dhl"),
        "apple" to listOf("apple", "icloud"),
        "microsoft" to listOf("microsoft", "outlook", "office", "live.com"),
        "telekom" to listOf("telekom", "t-online"),
        "vodafone" to listOf("vodafone"),
        "ebay" to listOf("ebay"),
        "netflix" to listOf("netflix"),
        "elster" to listOf("elster"),
        "finanzamt" to listOf("elster", "finanzamt"),
        "mastercard" to listOf("mastercard"),
        "visa" to listOf("visa"),
    )

    private val ANCHOR = Regex(
        """<a\s[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG = Regex("<[^>]+>")
    private val DOMAIN_LIKE = Regex("""^(https?://)?(www\.)?([a-z0-9-]+\.)+[a-z]{2,}(/\S*)?$""", RegexOption.IGNORE_CASE)
    private val IP_HOST = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val EMBEDDED_ADDRESS = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

    /**
     * Returns human-readable reasons why the message might be phishing; empty if nothing suspicious was found.
     */
    fun phishingReasons(context: Context, info: MessageViewInfo): List<String> {
        val message = info.message ?: return emptyList()
        val reasons = mutableListOf<String>()
        val from = message.from?.firstOrNull()
        val address = from?.address?.lowercase().orEmpty()
        val domain = address.substringAfterLast('@', "")
        val name = from?.personal.orEmpty()

        // 1. Display name pretends to be a brand, but the address belongs to someone else
        val lowerName = name.lowercase()
        if (domain.isNotEmpty()) {
            for ((brand, domains) in BRANDS) {
                if (Regex("""\b${Regex.escape(brand)}\b""").containsMatchIn(lowerName) &&
                    domains.none { domain.contains(it) }
                ) {
                    reasons.add(context.getString(R.string.dcmail_phishing_brand, name, domain))
                    break
                }
            }
        }

        // 2. Display name contains a different e-mail address
        EMBEDDED_ADDRESS.find(name)?.value?.lowercase()?.let { shown ->
            if (shown != address) reasons.add(context.getString(R.string.dcmail_phishing_name_address, shown, address))
        }

        // 3. The receiving server could not verify the sender
        val auth = message.getHeader("Authentication-Results").joinToString(" ").lowercase()
        if (Regex("""\bdmarc=fail\b""").containsMatchIn(auth) || Regex("""\bspf=fail\b""").containsMatchIn(auth)) {
            reasons.add(context.getString(R.string.dcmail_phishing_auth_failed))
        }

        // 4. Links whose visible text shows a different address than where they lead
        val html = info.text.orEmpty()
        var linkReasons = 0
        for (match in ANCHOR.findAll(html)) {
            if (linkReasons >= 2) break
            val href = match.groupValues[1].trim()
            if (!href.startsWith("http", ignoreCase = true)) continue
            val host = hostOf(href) ?: continue
            val text = TAG.replace(match.groupValues[2], "").replace("&nbsp;", " ").trim()

            if (IP_HOST.matches(host)) {
                reasons.add(context.getString(R.string.dcmail_phishing_ip_link, host))
                linkReasons++
                continue
            }
            if (host.startsWith("xn--") || host.contains(".xn--")) {
                reasons.add(context.getString(R.string.dcmail_phishing_punycode, host))
                linkReasons++
                continue
            }
            if (DOMAIN_LIKE.matches(text)) {
                val shownHost = hostOf(if (text.contains("://")) text else "https://$text") ?: continue
                if (baseDomain(shownHost) != baseDomain(host)) {
                    reasons.add(context.getString(R.string.dcmail_phishing_link, shownHost, host))
                    linkReasons++
                }
            }
        }
        return reasons.distinct()
    }

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")
    } catch (e: RuntimeException) {
        null
    }

    /** Last two labels, or three for second-level registries like co.uk / com.au. */
    private fun baseDomain(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return host
        val secondLevel = labels[labels.size - 2]
        val keep = if (secondLevel in setOf("co", "com", "org", "net", "ac", "gov") && labels.last().length == 2) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    // --- invoices ---

    private val INVOICE_WORDS = Regex(
        """rechnung|invoice|gutschrift|quittung|zahlungserinnerung|mahnung|receipt|beleg|faktura""",
        RegexOption.IGNORE_CASE,
    )

    fun isInvoice(info: MessageViewInfo): Boolean {
        val attachments = info.attachments.orEmpty()
        val documents = attachments.filter { attachment ->
            val name = attachment.displayName.orEmpty().lowercase()
            val type = attachment.mimeType.orEmpty().lowercase()
            type == "application/pdf" || name.endsWith(".pdf") ||
                (name.endsWith(".xml") && (name.contains("rechnung") || name.contains("invoice")))
        }
        if (documents.isEmpty()) return false
        val subject = info.subject ?: info.message?.subject.orEmpty()
        return INVOICE_WORDS.containsMatchIn(subject) ||
            documents.any { INVOICE_WORDS.containsMatchIn(it.displayName.orEmpty()) }
    }

    fun senderAddress(message: Message?): String? =
        message?.from?.firstOrNull()?.address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun senderLabel(message: Message?): String {
        val from: Address = message?.from?.firstOrNull() ?: return ""
        return from.personal?.takeIf { it.isNotBlank() } ?: from.address.orEmpty()
    }
}
