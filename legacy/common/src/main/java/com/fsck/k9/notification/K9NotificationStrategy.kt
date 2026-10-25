package com.fsck.k9.notification

import app.k9mail.core.android.common.contact.ContactRepository
import app.k9mail.legacy.di.DI
import com.fsck.k9.QuietTimeChecker
import com.fsck.k9.mail.K9MailLib
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalMessage
import java.util.Calendar
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.common.mail.toEmailAddressOrNull
import net.thunderbird.legacy.logging.Log
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.core.preference.notification.NotificationPreference
import net.thunderbird.feature.account.AccountId

private const val TAG = "K9NotificationStrategy"

class K9NotificationStrategy(
    private val contactRepository: ContactRepository,
    private val generalSettingsManager: GeneralSettingsManager,
    private val accountManager: LegacyAccountManager,
) : NotificationStrategy {

    @Suppress("ReturnCount")
    override fun shouldNotifyForMessage(
        accountId: AccountId,
        localFolder: LocalFolder,
        message: LocalMessage,
        isOldMessage: Boolean,
    ): Boolean {
        val account = accountManager.findById(accountId) ?: return false

        // DC Mail: subject keywords per account
        val subject = message.subject
        if (DcNotifyKeywords.matches(DcNotifyKeywords.Kind.NEVER, accountId.toString(), subject)) {
            Log.v(TAG, "No notification: Subject contains a never-notify keyword")
            return false
        }
        val isDcAlways = DcNotifyKeywords.matches(DcNotifyKeywords.Kind.ALWAYS, accountId.toString(), subject)

        if (!isDcAlways && account.isDcQuietNow) {
            Log.v(TAG, "No notification: Quiet days or quiet hours of this account")
            return false
        }

        if (!isDcAlways && generalSettingsManager.getConfig().notification.isQuietDay) {
            Log.v(TAG, "No notification: Today is a quiet day")
            return false
        }

        if (!isDcAlways &&
            !generalSettingsManager.getConfig().notification.isNotificationDuringQuietTimeEnabled &&
            generalSettingsManager.getConfig().notification.isQuietTime
        ) {
            Log.v(TAG, "No notification: Quiet time is active")
            return false
        }

        if (!account.isNotifyNewMail) {
            Log.v(TAG, "No notification: Notifications are disabled")
            return false
        }

        if (!localFolder.isVisible) {
            Log.v(TAG, "No notification: Message is in folder not being displayed")
            return false
        }

        if (!localFolder.isNotificationsEnabled) {
            Log.v(TAG, "No notification: Notifications are not enabled for this folder")
            return false
        }

        if (isOldMessage) {
            Log.v(TAG, "No notification: Message is old")
            return false
        }

        if (message.isSet(Flag.SEEN)) {
            Log.v(TAG, "No notification: Message is marked as read")
            return false
        }

        if (account.isIgnoreChatMessages && message.isChatMessage) {
            Log.v(TAG, "No notification: Notifications for chat messages are disabled")
            return false
        }

        if (!account.isNotifySelfNewMail && account.isAnIdentity(message.from)) {
            Log.v(TAG, "No notification: Notifications for messages from yourself are disabled")
            return false
        }

        if (account.isNotifyContactsMailOnly &&
            !contactRepository.hasAnyContactFor(message.from.asList().mapNotNull { it.address.toEmailAddressOrNull() })
        ) {
            Log.v(TAG, "No notification: Message is not from a known contact")
            return false
        }

        return true
    }

    private val Message.isChatMessage: Boolean
        get() = getHeader(K9MailLib.CHAT_HEADER).isNotEmpty()

    @OptIn(ExperimentalTime::class)
    private val LegacyAccount.isDcQuietNow: Boolean
        get() {
            if (dcQuietDays.isEmpty() && !isDcQuietTimeEnabled) {
                return false
            }

            return QuietTimeChecker(
                clock = DI.get<Clock>(),
                quietTimeStart = dcQuietTimeStarts,
                quietTimeEnd = dcQuietTimeEnds,
                isQuietTimeEnabled = isDcQuietTimeEnabled,
                quietDays = dcQuietDays,
            ).isQuietTime
        }

    @OptIn(ExperimentalTime::class)
    private val NotificationPreference.isQuietDay: Boolean
        get() {
            if (quietDays.isEmpty()) {
                return false
            }

            val calendar = Calendar.getInstance()
            calendar.timeInMillis = DI.get<Clock>().now().toEpochMilliseconds()
            return calendar[Calendar.DAY_OF_WEEK] in quietDays
        }

    @OptIn(ExperimentalTime::class)
    private val NotificationPreference.isQuietTime: Boolean
        get() {
            if (!isQuietTimeEnabled && quietDays.isEmpty()) {
                return false
            }

            val clock = DI.get<Clock>()
            val quietTimeChecker = QuietTimeChecker(
                clock = clock,
                quietTimeStart = quietTimeStarts,
                quietTimeEnd = quietTimeEnds,
                isQuietTimeEnabled = isQuietTimeEnabled,
                quietDays = quietDays,
            )
            return quietTimeChecker.isQuietTime
        }
}
