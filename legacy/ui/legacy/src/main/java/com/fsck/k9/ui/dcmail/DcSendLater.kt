package com.fsck.k9.ui.dcmail

import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import app.k9mail.legacy.di.DI
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.ui.R
import java.util.Calendar
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory

/**
 * DC Mail: lets the user pick a time for sending a message later.
 *
 * The message waits in the Outbox with the header [HEADER] until that time. An alarm then starts sending the Outbox;
 * MessagingController skips messages whose time hasn't come yet and removes the header before sending.
 */
object DcSendLater {
    const val HEADER = "X-DC-Send-At"

    private const val EXTRA_ACCOUNT_ID = "accountId"
    private const val MORNING_HOUR = 8
    private const val AFTERNOON_HOUR = 14

    fun interface OnTimePicked {
        fun onPicked(timestamp: Long)
    }

    @JvmStatic
    fun pickTime(activity: Activity, callback: OnTimePicked) {
        val tomorrowMorning = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayAfternoon = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, AFTERNOON_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextMonday = Calendar.getInstance().apply {
            do {
                add(Calendar.DAY_OF_YEAR, 1)
            } while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val presets = mutableListOf<Pair<String, Long>>()
        if (todayAfternoon.timeInMillis > System.currentTimeMillis()) {
            presets.add(activity.getString(R.string.dcmail_send_later_today_afternoon) to todayAfternoon.timeInMillis)
        }
        presets.add(activity.getString(R.string.dcmail_send_later_tomorrow_morning) to tomorrowMorning.timeInMillis)
        presets.add(activity.getString(R.string.dcmail_send_later_monday_morning) to nextMonday.timeInMillis)

        val labels = presets.map { it.first }.toMutableList()
        labels.add(activity.getString(R.string.dcmail_send_later_custom))

        AlertDialog.Builder(activity)
            .setTitle(R.string.dcmail_send_later)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    callback.onPicked(presets[which].second)
                } else {
                    pickCustomTime(activity, callback)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickCustomTime(activity: Activity, callback: OnTimePicked) {
        val now = Calendar.getInstance()
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        val chosen = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (chosen.timeInMillis <= System.currentTimeMillis()) {
                            android.widget.Toast.makeText(
                                activity,
                                R.string.dcmail_send_later_past,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            callback.onPicked(chosen.timeInMillis)
                        }
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(activity),
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = now.timeInMillis - 1000 }.show()
    }

    @JvmStatic
    fun formatTime(context: Context, timestamp: Long): String {
        val date = DateFormat.getMediumDateFormat(context).format(timestamp)
        val time = DateFormat.getTimeFormat(context).format(timestamp)
        return "$date, $time"
    }

    /**
     * Wakes the app at [timestamp] to send the Outbox of the given account.
     */
    @JvmStatic
    fun schedule(context: Context, accountId: AccountId, timestamp: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DcSendLaterReceiver::class.java)
            .putExtra(EXTRA_ACCOUNT_ID, accountId.toString())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (timestamp / 1000 % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact but battery friendly; may be delayed by a few minutes in doze
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
    }

    internal fun accountIdFrom(intent: Intent): AccountId? {
        val raw = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: return null
        return try {
            AccountIdFactory.of(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * DC Mail: sends the Outbox when a scheduled message is due.
 */
class DcSendLaterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountId = DcSendLater.accountIdFrom(intent) ?: return
        DI.get(MessagingController::class.java).sendPendingMessages(accountId, null)
    }
}
