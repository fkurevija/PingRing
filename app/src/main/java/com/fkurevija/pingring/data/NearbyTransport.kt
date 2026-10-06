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
    val receivedInviteSecret: SharedFlow<InviteSecretPayloadDto>
    val receivedBleIdentityKey: SharedFlow<BleIdentityKeyPayloadDto>
    val receivedMemberDeparture: SharedFlow<MemberDeparturePayloadDto>
    val receivedMeshPresence: SharedFlow<MeshPresencePayloadDto>
    val receivedPeerRanging: SharedFlow<PeerRangingPayloadDto>
    val receivedChatColourClaim: SharedFlow<ChatColourClaimPayloadDto>
    val receivedEventControl: SharedFlow<EventControlPayloadDto>
    val peerNames: SharedFlow<Pair<String, String>>

    /**
     * Debug-only: scripted range reports from tools/mock_radar.py and local
     * physical-device Wi-Fi RSSI proxies for admitted peers.
     *
     * Real devices never send these, so [NearbyConnectionsManager] never
     * emits any; only the LAN debug transport can deliver them. Anything
     * consuming this flow must therefore be a debug-only path as well.
     */
    val receivedMockRanging: SharedFlow<MockRangingDto>

    /** The host's standing answer to devices asking to join. */
    var acceptsNewMembers: Boolean
    var inviteSecretProvider: (() -> String?)?
    var userNameProvider: (() -> String?)?

    /**
     * Devices asking to join, oldest first, for the host to act on.
     *
     * Only ever non-empty on a host, and only while it is holding a request
     * open. Both transports hold code-only joins for approval; a valid QR
     * invitation bypasses the approval card.
     */
    val pendingJoinRequests: StateFlow<List<PendingJoinRequest>>
    val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>>

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
    fun confirmConnectionAuthentication(endpointId: String)
    fun rejectConnectionAuthentication(endpointId: String)

    fun startAdvertising(eventId: String, localPeerId: String, useWifiDirect: Boolean, useHotspot: Boolean)
    fun startDiscovery(eventId: String, localPeerId: String)
    fun connectToPeer(peerId: String)
    fun stopAll()

    /** Announces closure out of band for transports that provide that channel; Nearby uses EventControl. */
    fun announceEventClosed()

    fun sendUwbParameters(peerId: String, params: Any)
    fun broadcastUwbParameters(params: Any)

    /**
     * A UWB ranging offer forwarded by the host on behalf of one guest and
     * aimed at another.
     *
    * Only a guest ever sends one. The production mesh routes it to the named
    * guest; any authenticated relay can forward it.
     */
    val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto>

    /**
    * Routes a [UwbRangingOfferDto] to the guest named in its targetPeerId.
    * The host remains trusted to read the STS key when it is on the route.
     */
    fun sendUwbRangingOfferToHost(params: Any)

    fun broadcastChatMessage(chatMsg: ChatPayloadDto)
    fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>)
    fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto)

    fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto)
    fun broadcastBeacon(beacon: BeaconPayloadDto)
    fun broadcastPeerRanging(ranging: PeerRangingPayloadDto)
    fun sendInviteSecret(peerId: String, secret: InviteSecretPayloadDto)
    fun sendBleIdentityKey(peerId: String, identityKey: BleIdentityKeyPayloadDto)
    fun broadcastBleIdentityKey(identityKey: BleIdentityKeyPayloadDto)
    fun announceMemberDeparture(departure: MemberDeparturePayloadDto, onComplete: () -> Unit = {})
    fun broadcastMemberDeparture(departure: MemberDeparturePayloadDto)
    fun sendJoinProofToHost(proof: JoinProofPayloadDto)
    fun sendEventControl(peerId: String, control: EventControlPayloadDto)

    /**
     * Claims a chat bubble colour, or answers somebody else's claim.
     *
     * Broadcast rather than targeted because a guest's claim has to reach the host
     * and the host's answer has to reach every guest: the message carries both
     * directions in [ChatColourClaimPayloadDto.granted], and a member that only
     * heard about one colour would grey out the wrong swatches.
     */
    fun broadcastChatColourClaim(claim: ChatColourClaimPayloadDto)
}
