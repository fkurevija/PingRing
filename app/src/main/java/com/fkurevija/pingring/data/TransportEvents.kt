package com.fkurevija.pingring.data

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.fkurevija.pingring.data.chat.BeaconPayloadDto
import com.fkurevija.pingring.data.chat.ChatHistoryPayloadDto
import com.fkurevija.pingring.data.chat.ChatPayloadDto
import com.fkurevija.pingring.data.chat.ChatReceiptPayloadDto
import com.fkurevija.pingring.data.radar.PeerRangingPayloadDto
import com.fkurevija.pingring.data.ranging.MockRangingDto
import com.fkurevija.pingring.data.ranging.UwbControllerParametersDto
import com.fkurevija.pingring.data.ranging.UwbPeerAddressDto
import com.fkurevija.pingring.data.ranging.UwbRangingOfferDto
import com.fkurevija.pingring.data.session.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.session.ChatColourClaimPayloadDto
import com.fkurevija.pingring.data.session.EventControlPayloadDto
import com.fkurevija.pingring.data.session.InviteSecretPayloadDto
import com.fkurevija.pingring.data.session.MemberDeparturePayloadDto
import com.fkurevija.pingring.data.session.MeshPresencePayloadDto

/**
 * Where a payload that arrived from a peer goes once its wire type has been
 * recognised: one method per kind, each named after the flow that carries it.
 *
 * [RelayCodec.route] does the dispatching, so the mapping from "a payload
 * arrived" to "this flow carries it" is written down once instead of once per
 * transport.
 */
internal interface PayloadSink {
    fun onChatMessage(message: ChatPayloadDto)
    fun onChatHistory(history: ChatHistoryPayloadDto)
    fun onChatReceipt(receipt: ChatReceiptPayloadDto)
    fun onBeacon(beacon: BeaconPayloadDto)
    fun onInviteSecret(secret: InviteSecretPayloadDto)
    fun onBleIdentityKey(identityKey: BleIdentityKeyPayloadDto)
    fun onMemberDeparture(departure: MemberDeparturePayloadDto)
    fun onMeshPresence(presence: MeshPresencePayloadDto)
    fun onPeerRanging(ranging: PeerRangingPayloadDto)
    fun onChatColourClaim(claim: ChatColourClaimPayloadDto)
    fun onEventControl(control: EventControlPayloadDto)
    fun onUwbAddress(address: UwbPeerAddressDto)
    fun onUwbParameters(params: UwbControllerParametersDto)
}

/**
 * The received side of a [NearbyTransport], in one place.
 *
 * Both transports expose the same ten flows with the same buffer sizes
 * (scripted range reports being the one only one of them can fill). They used to
 * declare a copy of every one of them, which meant the contract
 * [DiscoveryRepository] consumes - how deep each buffer is, and what an overflow
 * drops - was written down twice and could drift apart silently, because a
 * consumer cannot tell a deeper buffer from a shallower one, only that a message
 * it expected never arrived.
 *
 * [tag] is the transport's logcat tag, so an arriving payload is reported
 * through the transport that actually received it.
 */
internal class TransportEvents(private val tag: String) : PayloadSink {

    private val _pendingAuthentications =
        MutableStateFlow<List<ConnectionAuthenticationRequest>>(emptyList())
    val pendingAuthentications: StateFlow<List<ConnectionAuthenticationRequest>> =
        _pendingAuthentications.asStateFlow()

    fun setPendingAuthentications(requests: List<ConnectionAuthenticationRequest>) {
        _pendingAuthentications.value = requests
    }

    fun removePendingAuthentication(endpointId: String) {
        _pendingAuthentications.value =
            _pendingAuthentications.value.filterNot { it.endpointId == endpointId }
    }

    private val _receivedPeerAddress = MutableSharedFlow<UwbPeerAddressDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedPeerAddress: SharedFlow<UwbPeerAddressDto> = _receivedPeerAddress.asSharedFlow()

    private val _receivedControllerParams = MutableSharedFlow<UwbControllerParametersDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedControllerParams: SharedFlow<UwbControllerParametersDto> = _receivedControllerParams.asSharedFlow()

    private val _receivedUwbRangingOffer = MutableSharedFlow<UwbRangingOfferDto>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedUwbRangingOffer: SharedFlow<UwbRangingOfferDto> = _receivedUwbRangingOffer.asSharedFlow()

    private val _receivedChatMessage = MutableSharedFlow<ChatPayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedChatMessage: SharedFlow<ChatPayloadDto> = _receivedChatMessage.asSharedFlow()

    private val _receivedChatHistory = MutableSharedFlow<ChatHistoryPayloadDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedChatHistory: SharedFlow<ChatHistoryPayloadDto> = _receivedChatHistory.asSharedFlow()

    private val _receivedChatReceipt = MutableSharedFlow<ChatReceiptPayloadDto>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedChatReceipt: SharedFlow<ChatReceiptPayloadDto> = _receivedChatReceipt.asSharedFlow()

    private val _connectionEstablished = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val connectionEstablished: SharedFlow<String> = _connectionEstablished.asSharedFlow()

    private val _peerDisconnected = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val peerDisconnected: SharedFlow<String> = _peerDisconnected.asSharedFlow()

    private val _eventClosed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventClosed: SharedFlow<Unit> = _eventClosed.asSharedFlow()

    private val _receivedBeacon = MutableSharedFlow<BeaconPayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedBeacon: SharedFlow<BeaconPayloadDto> = _receivedBeacon.asSharedFlow()

    private val _receivedInviteSecret = MutableSharedFlow<InviteSecretPayloadDto>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedInviteSecret: SharedFlow<InviteSecretPayloadDto> = _receivedInviteSecret.asSharedFlow()

    private val _receivedBleIdentityKey = MutableSharedFlow<BleIdentityKeyPayloadDto>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedBleIdentityKey: SharedFlow<BleIdentityKeyPayloadDto> = _receivedBleIdentityKey.asSharedFlow()

    private val _receivedMemberDeparture = MutableSharedFlow<MemberDeparturePayloadDto>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedMemberDeparture: SharedFlow<MemberDeparturePayloadDto> = _receivedMemberDeparture.asSharedFlow()

    private val _receivedMeshPresence = MutableSharedFlow<MeshPresencePayloadDto>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedMeshPresence: SharedFlow<MeshPresencePayloadDto> = _receivedMeshPresence.asSharedFlow()

    /**
     * Chat colour claims and the host's answers to them.
     *
     * A request and a reply travel the same flow because they are the same message
     * with [ChatColourClaimPayloadDto.granted] set or unset, and a guest has to
     * tell a reply from its own request echoing back - hence the discriminator is
     * on the payload rather than on the channel.
     */
    private val _receivedChatColourClaim = MutableSharedFlow<ChatColourClaimPayloadDto>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedChatColourClaim: SharedFlow<ChatColourClaimPayloadDto> =
        _receivedChatColourClaim.asSharedFlow()
    private val _receivedPeerRanging = MutableSharedFlow<PeerRangingPayloadDto>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedPeerRanging: SharedFlow<PeerRangingPayloadDto> = _receivedPeerRanging.asSharedFlow()

    private val _receivedEventControl = MutableSharedFlow<EventControlPayloadDto>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedEventControl: SharedFlow<EventControlPayloadDto> = _receivedEventControl.asSharedFlow()

    /**
     * Scripted range reports arrive at a much higher rate than any real message
     * (up to a few per second per peer) and a dropped sample is worthless, so
     * this one buffer is deeper and keeps the newest samples.
     *
     * Only the LAN debug transport can ever fill it: a real device has no way of
     * being told a distance it did not measure itself.
     */
    private val _receivedMockRanging = MutableSharedFlow<MockRangingDto>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedMockRanging: SharedFlow<MockRangingDto> = _receivedMockRanging.asSharedFlow()

    private val _peerNames = MutableSharedFlow<Pair<String, String>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val peerNames: SharedFlow<Pair<String, String>> = _peerNames.asSharedFlow()

    fun onPeerConnected(peerId: String) {
        _connectionEstablished.tryEmit(peerId)
    }

    fun onPeerName(peerId: String, name: String) {
        if (peerId.isNotBlank() && name.isNotBlank()) {
            _peerNames.tryEmit(peerId to name)
        }
    }

    fun onPeerDisconnected(peerId: String) {
        _peerDisconnected.tryEmit(peerId)
    }

    fun onEventClosed() {
        _eventClosed.tryEmit(Unit)
    }

    /** A ranging offer that arrived at the guest it names. */
    fun onUwbRangingOfferReceived(offer: UwbRangingOfferDto) {
        _receivedUwbRangingOffer.tryEmit(offer)
    }

    fun onMockRanging(report: MockRangingDto) {
        _receivedMockRanging.tryEmit(report)
    }

    override fun onChatMessage(message: ChatPayloadDto) {
        Log.i(tag, "Received chat message ${message.id} from peer ${message.senderPeerId}")
        _receivedChatMessage.tryEmit(message)
    }

    override fun onChatHistory(history: ChatHistoryPayloadDto) {
        Log.i(tag, "Received chat history with ${history.messages.size} msgs")
        _receivedChatHistory.tryEmit(history)
    }

    override fun onChatReceipt(receipt: ChatReceiptPayloadDto) {
        _receivedChatReceipt.tryEmit(receipt)
    }

    override fun onBeacon(beacon: BeaconPayloadDto) {
        Log.i(tag, "Received beacon ${beacon.id} from peer ${beacon.senderPeerId}")
        _receivedBeacon.tryEmit(beacon)
    }

    override fun onInviteSecret(secret: InviteSecretPayloadDto) {
        _receivedInviteSecret.tryEmit(secret)
    }

    override fun onBleIdentityKey(identityKey: BleIdentityKeyPayloadDto) {
        _receivedBleIdentityKey.tryEmit(identityKey)
    }

    override fun onMemberDeparture(departure: MemberDeparturePayloadDto) {
        _receivedMemberDeparture.tryEmit(departure)
    }

    override fun onMeshPresence(presence: MeshPresencePayloadDto) {
        _receivedMeshPresence.tryEmit(presence)
    }

    override fun onChatColourClaim(claim: ChatColourClaimPayloadDto) {
        _receivedChatColourClaim.tryEmit(claim)
    }

    override fun onPeerRanging(ranging: PeerRangingPayloadDto) {
        _receivedPeerRanging.tryEmit(ranging)
    }

    override fun onEventControl(control: EventControlPayloadDto) {
        _receivedEventControl.tryEmit(control)
    }

    override fun onUwbAddress(address: UwbPeerAddressDto) {
        _receivedPeerAddress.tryEmit(address)
    }

    override fun onUwbParameters(params: UwbControllerParametersDto) {
        _receivedControllerParams.tryEmit(params)
    }
}
