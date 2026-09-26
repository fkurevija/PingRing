package com.fkurevija.pingring.data

import kotlinx.coroutines.flow.SharedFlow

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
    val receivedNudge: SharedFlow<NudgePayloadDto>
    val receivedBeacon: SharedFlow<BeaconPayloadDto>
    var acceptsNewMembers: Boolean

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

    fun broadcastChatMessage(chatMsg: ChatPayloadDto)
    fun sendChatHistoryToPeer(peerId: String, messages: List<ChatPayloadDto>)
    fun sendChatMessageToPeer(peerId: String, chatMsg: ChatPayloadDto)

    fun sendNudgeToPeer(peerId: String, nudge: NudgePayloadDto)
    fun broadcastNudge(nudge: NudgePayloadDto)

    fun sendBeaconToPeer(peerId: String, beacon: BeaconPayloadDto)
    fun broadcastBeacon(beacon: BeaconPayloadDto)
}
