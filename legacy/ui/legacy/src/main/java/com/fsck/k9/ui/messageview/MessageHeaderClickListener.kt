package com.fsck.k9.ui.messageview

import android.view.View

interface MessageHeaderClickListener {
    fun onParticipantsContainerClick()
    fun onMenuItemClick(itemId: Int)
    fun onViewAllAttachmentsClick()

    /** DC Mail: tap on the sender name or picture. */
    fun onSenderClick(anchor: View)
}
