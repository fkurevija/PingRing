package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the peer-to-peer transport used to discover and exchange
 * messages with nearby devices for a PingRing event.
 *
 * [NearbyConnectionsManager] is the production implementation backed by
 * Google Play Services Nearby Connections (BLE / Wi-Fi Direct / Bluetooth).
 * [LanDebugConnectionsManager] is a debug-only implementation backed by
 * plain LAN sockets, useful for testing against the Android Emulator, which
 * has no real Bluetooth/Wi-Fi Direct radios and therefore cannot use
 * Nearby Connections at all.
 */
interface NearbyTransport {
    val receivedPeerAddress: SharedFlow<UwbPeerAddressDto>
    val receivedControllerParams: SharedFlow<UwbControllerParametersDto>
    val receivedChatMessage: SharedFlow<ChatPayloadDto>
    val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto>
    val connectionEstablished: SharedFlow<String>
    val peerDisconnected: SharedFlow<String>
    val eventClosed: SharedFlow<Unit>
    val receivedBeacon: SharedFlow<BeaconPayloadDto>

    /**
     * Debug-only: scripted range reports for a peer, as produced by
     * tools/mock_radar.py.
     *
     * Real devices never send these, so [NearbyConnectionsManager] never
     * emits any; only the LAN debug transport can deliver them, and only
     * while its relay is being fed a script. Anything consuming this flow must
     * therefore be a debug-only path as well.
     */
    val receivedMockRanging: SharedFlow<MockRangingDto>

    /** The host's standing answer to devices asking to join. */
    var acceptsNewMembers: Boolean

    /**
     * Whether a device asking to join is held for the host to approve rather
     * than admitted on arrival.
     *
     * Off by default, so upgrading does not put a prompt in front of a host who
     * never asked for one: a join that used to be automatic is a decision only
     * the host can make, and taking it by default would silently stop people
     * joining events they were previously able to.
     */
    var requiresApproval: Boolean

    /**
     * Devices asking to join, oldest first, for the host to act on.
     *
     * Only ever non-empty on a host, and only while it is holding a request
     * open. The LAN debug transport never emits any: a debug relay has no
     * admission handshake to interrupt, and inventing one would make the two
     * transports behave differently for no reason.
     */
    val pendingJoinRequests: StateFlow<List<PendingJoinRequest>>

    /** Lets a held request in. Ignored if the request is no longer pending. */
    fun approveJoinRequest(endpointId: String)

    /**
     * Turns a held request away, telling the guest.
     *
     * Ignoring an unknown endpoint is deliberate: a request can expire or the
     * guest can vanish between the host tapping Deny and this arriving, and that
     * is a normal race rather than an error worth surfacing.
     */
    fun denyJoinRequest(endpointId: String)

    fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean)
    fun startDiscovery(eventId: String, localPeerId: String)
    fun stopAll()

    /**
     * Announces that this host is ending the PingRing, so peers can leave it
     * too. [NearbyConnectionsManager] has nothing to do here - nearby devices
     * see the host's BLE closure advertisement - while the LAN debug transport
     * asks the relay to close the event and disconnect its members.
     */
    fun announceEventClosed()

    fun sendUwbParameters(peerId: String, params: Any)
    fun broadcastUwbParameters(params: Any)

    /**
     * A UWB ranging offer forwarded by the host on behalf of one guest and
     * aimed at another.
     *
     * Only a guest ever sends one, and only ever to the host, which is the
     * only peer it has a transport connection to under P2P_STAR.
     */
    val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto>

    /**
     * Sends a [UwbRangingOfferDto] up to the host, which forwards it to the
     * guest named in its targetPeerId. This is how two guests agree on the
     * channel and STS key a UWB session needs without a direct connection
     * between them.
     */
    fun sendUwbRangingOfferToHost(params: Any)

    fun broadcastChatMessage(chatMsg: ChatPayloadDto)
    fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>)
    fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto)

    fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto)
    fun broadcastBeacon(beacon: BeaconPayloadDto)
}
