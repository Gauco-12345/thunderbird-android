package com.fsck.k9.ui.dcmail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.WorkerThread
import app.k9mail.legacy.di.DI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * DC Mail: loads the favicon of a sender's domain for the message list.
 *
 * Icons are fetched directly from the sender's domain over HTTPS (no third-party icon service) and cached on the
 * device, so every domain is requested at most once. Domains without an icon are remembered for a while.
 */
object DcFaviconLoader {
    private const val PREFERENCES_NAME = "dcmail_favicons"
    private const val KEY_ENABLED = "enabled"
    private const val CACHE_DIR = "dcmail_favicons"
    private const val MISSING_SUFFIX = ".none"
    private const val MISSING_RETRY_MS = 7L * 24 * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 512 * 1024
    private const val MIN_ICON_SIZE = 16
    private const val ICON_SCALE = 0.72f
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    /** Webmail providers whose favicon says nothing about the sender. */
    private val FREEMAIL_DOMAINS = setOf(
        "gmail.com", "googlemail.com", "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "t-online.de",
        "outlook.com", "outlook.de", "hotmail.com", "hotmail.de", "live.com", "live.de", "msn.com",
        "yahoo.com", "yahoo.de", "icloud.com", "me.com", "mac.com", "aol.com", "aol.de", "freenet.de",
        "arcor.de", "posteo.de", "posteo.net", "mailbox.org", "protonmail.com", "proton.me", "pm.me",
        "online.de", "email.de", "vodafone.de", "1und1.de",
    )

    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Returns a square bitmap of [size] pixels showing the domain's favicon, or `null` to fall back to the letter.
     */
    @JvmStatic
    @WorkerThread
    fun loadAvatar(emailAddress: String, size: Int): Bitmap? {
        val context = try {
            DI.get(Context::class.java)
        } catch (e: RuntimeException) {
            return null
        }
        if (!isEnabled(context) || size <= 0) return null

        val domain = emailAddress.substringAfterLast('@', "").trim().lowercase(Locale.ROOT)
        if (!isValidDomain(domain) || domain in FREEMAIL_DOMAINS) return null

        val icon = loadIcon(context, domain) ?: return null
        return drawAvatar(icon, size)
    }

    private fun loadIcon(context: Context, domain: String): Bitmap? {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        val cached = File(cacheDir, "$domain.png")
        val missing = File(cacheDir, domain + MISSING_SUFFIX)

        synchronized(lock) {
            if (cached.exists()) {
                BitmapFactory.decodeFile(cached.path)?.let { return it }
            }
            if (missing.exists() && System.currentTimeMillis() - missing.lastModified() < MISSING_RETRY_MS) {
                return null
            }
        }

        val bitmap = download(domain)

        synchronized(lock) {
            cacheDir.mkdirs()
            if (bitmap != null) {
                try {
                    cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    missing.delete()
                } catch (e: IOException) {
                    cached.delete()
                }
            } else {
                try {
                    missing.writeText("")
                    missing.setLastModified(System.currentTimeMillis())
                } catch (e: IOException) {
                    // Not cached; the next attempt simply tries again
                }
            }
        }

        return bitmap
    }

    private fun download(domain: String): Bitmap? {
        val hosts = buildList {
            add(domain)
            val labels = domain.split('.')
            if (labels.size > 2) add(labels.takeLast(2).joinToString("."))
        }.distinct()

        for (host in hosts) {
            for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
                fetchBitmap("https://$host$path")?.let { return it }
            }
        }
        return null
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }

        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DC-Mail")

            if (connection.responseCode !in HTTP_OK_MIN..HTTP_OK_MAX) return null
            if (connection.url.protocol != "https") return null

            val bytes = readLimited(connection) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width < MIN_ICON_SIZE || bitmap.height < MIN_ICON_SIZE) null else bitmap
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(connection: HttpURLConnection): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_BYTES) return null
            }
        }
        return output.toByteArray()
    }

    private fun drawAvatar(icon: Bitmap, size: Int): Bitmap {
        val avatar = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(avatar)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        paint.color = Color.WHITE
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)

        val iconSize = size * ICON_SCALE
        val offset = (size - iconSize) / 2f
        canvas.drawBitmap(
            icon,
            Rect(0, 0, icon.width, icon.height),
            RectF(offset, offset, offset + iconSize, offset + iconSize),
            paint,
        )
        return avatar
    }

    private fun isValidDomain(domain: String): Boolean {
        return domain.length in 4..253 &&
            domain.contains('.') &&
            domain.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
            !domain.startsWith('.') && !domain.endsWith('.')
    }
}
