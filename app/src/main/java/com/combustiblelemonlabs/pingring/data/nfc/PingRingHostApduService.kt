package com.combustiblelemonlabs.pingring.data.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class PingRingHostApduService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        NfcInviteApdu.respond(commandApdu, NfcInviteExchange.currentInvite())

    override fun onDeactivated(reason: Int) = Unit
}
