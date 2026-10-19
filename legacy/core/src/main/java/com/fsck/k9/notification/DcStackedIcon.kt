package com.fsck.k9.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.k9mail.legacy.di.DI
import java.util.concurrent.ConcurrentHashMap

/**
 * DC Mail: builds a "stacked" status bar icon (two overlapping copies of the account's icon) that is used when
 * several new messages are waiting.
 */
internal object DcStackedIcon {
    private const val ICON_SIZE_DP = 24
    private const val COPY_SCALE = 0.74f
    private const val GAP_DP = 1.5f
    private const val GAP_STEPS = 8

    private val cache = ConcurrentHashMap<Int, IconCompat>()

    fun create(iconResId: Int): IconCompat? {
        cache[iconResId]?.let { return it }

        val context = try {
            DI.get(Context::class.java)
        } catch (e: RuntimeException) {
            return null
        }

        val icon = try {
            IconCompat.createWithBitmap(drawStacked(context, iconResId) ?: return null)
        } catch (e: RuntimeException) {
            return null
        }
        cache[iconResId] = icon
        return icon
    }

    private fun drawStacked(context: Context, iconResId: Int): Bitmap? {
        val density = context.resources.displayMetrics.density
        val size = (ICON_SIZE_DP * density).toInt().coerceAtLeast(1)
        val copySize = (size * COPY_SCALE).toInt()
        val offset = size - copySize
        val gap = GAP_DP * density

        val copy = renderDrawable(context, iconResId, copySize) ?: return null

        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Back copy: top right
        canvas.drawBitmap(copy, offset.toFloat(), 0f, paint)

        // Cut a small gap around the front copy so the two copies stay distinguishable in a monochrome icon
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        for (step in 0 until GAP_STEPS) {
            val angle = 2.0 * Math.PI * step / GAP_STEPS
            val dx = (Math.cos(angle) * gap).toFloat()
            val dy = (Math.sin(angle) * gap).toFloat()
            canvas.drawBitmap(copy, dx, offset + dy, clearPaint)
        }

        // Front copy: bottom left
        canvas.drawBitmap(copy, 0f, offset.toFloat(), paint)

        return result
    }

    private fun renderDrawable(context: Context, iconResId: Int, size: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, iconResId)?.mutate() ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}

/**
 * DC Mail: uses the stacked icon when there is more than one new message.
 */
internal fun NotificationCompat.Builder.setDcSmallIcon(iconResId: Int, messageCount: Int): NotificationCompat.Builder {
    val stacked = if (messageCount > 1) DcStackedIcon.create(iconResId) else null
    return if (stacked != null) setSmallIcon(stacked) else setSmallIcon(iconResId)
}
