package com.fkurevija.pingring.data.discovery

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fkurevija.pingring.data.Event
import com.fkurevija.pingring.data.EventControlPayloadDto
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.SessionManager

/**
 * Coordinates host/member actions that publish PingRing membership and event
 * state to the transport.
 */
internal class DiscoveryEventCoordinator(
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val connectedPeerIds: () -> Set<String>,
) {
    fun announceMemberDeparture(onComplete: () -> Unit = {}) {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent
        if (state.isHost || event == null) {
            onComplete()
            return
        }
        transport.announceMemberDeparture(
            departure = MemberDeparturePayloadDto(
                eventCode = event.id,
                peerId = sessionManager.peerId.value,
            ),
            onComplete = {
                Handler(Looper.getMainLooper()).post(onComplete)
            },
        )
    }

    /**
     * Sends a terminal host packet before the host clears its local session.
     *
     * The LAN debug transport has no BLE radio to advertise with, so it is
     * notified through [NearbyTransport.announceEventClosed] as well.
     */
    fun announceEventClosure(event: Event) {
        transport.announceEventClosed()
        broadcastEventControl(event, isClosed = true)
    }

    fun broadcastEventControl(event: Event, isClosed: Boolean = false) {
        val state = sessionManager.sessionState.value
        if (!state.isHost) return

        transport.acceptsNewMembers = event.acceptsNewMembers
        val control = EventControlPayloadDto(
            eventCode = event.id,
            hostPeerId = sessionManager.peerId.value,
            useUwb = event.useUwb,
            useGnss = event.useGnss,
            useWifiDirect = event.useWifiDirect,
            acceptsNewMembers = event.acceptsNewMembers,
            isFull = state.friends.size + 1 >= MAX_PARTICIPANTS,
            isClosed = isClosed,
        )
        connectedPeerIds().forEach { peerId ->
            transport.sendEventControl(peerId, control)
        }
    }

    fun removeMember(peerId: String) {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent
        if (!state.isHost || event == null || peerId == sessionManager.peerId.value ||
            state.friends.none { it.peerId == peerId }
        ) {
            Log.w(TAG, "Ignoring member removal for unknown peer or non-host session")
            return
        }

        val departure = MemberDeparturePayloadDto(event.id, peerId)
        val removalControl = EventControlPayloadDto(
            eventCode = event.id,
            hostPeerId = sessionManager.peerId.value,
            useUwb = event.useUwb,
            useGnss = event.useGnss,
            useWifiDirect = event.useWifiDirect,
            acceptsNewMembers = event.acceptsNewMembers,
            isFull = false,
            isClosed = true,
        )
        transport.removeMember(peerId, departure, removalControl)
    }

    private companion object {
        const val TAG = "DiscoveryRepository"
    }
}
