package com.fkurevija.pingring.data

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    fun onNudge(nudge: NudgePayloadDto)
    fun onBeacon(beacon: BeaconPayloadDto)
    fun onUwbAddress(address: UwbPeerAddressDto)
    fun onUwbParameters(params: UwbControllerParametersDto)
}

/**
 * The received side of a [NearbyTransport], in one place.
 *
 * Both transports expose the same eleven flows with the same buffer sizes
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

    private val _receivedNudge = MutableSharedFlow<NudgePayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedNudge: SharedFlow<NudgePayloadDto> = _receivedNudge.asSharedFlow()

    private val _receivedBeacon = MutableSharedFlow<BeaconPayloadDto>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedBeacon: SharedFlow<BeaconPayloadDto> = _receivedBeacon.asSharedFlow()

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

    fun onPeerConnected(peerId: String) {
        _connectionEstablished.tryEmit(peerId)
    }

    fun onPeerDisconnected(peerId: String) {
        _peerDisconnected.tryEmit(peerId)
    }

    fun onEventClosed() {
        _eventClosed.tryEmit(Unit)
    }

    /**
     * A ranging offer that arrived for the guest it names.
     *
     * Only the LAN debug transport delivers one: on a real device the host is the
     * single peer that can carry it, and it forwards the offer rather than
     * consuming it.
     */
    fun onUwbRangingOfferReceived(offer: UwbRangingOfferDto) {
        _receivedUwbRangingOffer.tryEmit(offer)
    }

    fun onMockRanging(report: MockRangingDto) {
        _receivedMockRanging.tryEmit(report)
    }

    override fun onChatMessage(message: ChatPayloadDto) {
        Log.i(tag, "Received chat message '${message.text}' from ${message.senderName} (peer ${message.senderPeerId})")
        _receivedChatMessage.tryEmit(message)
    }

    override fun onChatHistory(history: ChatHistoryPayloadDto) {
        Log.i(tag, "Received chat history with ${history.messages.size} msgs")
        _receivedChatHistory.tryEmit(history)
    }

    override fun onNudge(nudge: NudgePayloadDto) {
        Log.i(tag, "Received nudge from ${nudge.senderName}")
        _receivedNudge.tryEmit(nudge)
    }

    override fun onBeacon(beacon: BeaconPayloadDto) {
        Log.i(tag, "Received beacon '${beacon.title}' from ${beacon.senderName}")
        _receivedBeacon.tryEmit(beacon)
    }

    override fun onUwbAddress(address: UwbPeerAddressDto) {
        _receivedPeerAddress.tryEmit(address)
    }

    override fun onUwbParameters(params: UwbControllerParametersDto) {
        _receivedControllerParams.tryEmit(params)
    }
}

