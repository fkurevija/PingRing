package com.combustiblelemonlabs.pingring.data.nfc

import com.combustiblelemonlabs.pingring.data.session.InviteSecret
import com.combustiblelemonlabs.pingring.data.session.Event

/** Holds the foreground host's invite in memory only while its invite is visible. */
internal object NfcInviteExchange {
    @Volatile
    private var inviteValue: String? = null

    fun activate(eventCode: String, secret: String) {
        inviteValue = if (eventCode.matches(Regex("^[A-Fa-f0-9]{6}$")) &&
            InviteSecret.decode(secret) != null
        ) {
            "pingring://join?event=$eventCode&secret=$secret"
        } else {
            null
        }
    }

    fun deactivate(value: String) {
        if (inviteValue == value) inviteValue = null
    }

    fun currentInvite(): String? = inviteValue
}
