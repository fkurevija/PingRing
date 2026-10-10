package com.fkurevija.pingring.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * A DTO together with the wire type name that identifies it, i.e. one payload
 * ready to be wrapped in a [RelayEnvelope].
 */
internal data class EncodedPayload(val wireType: String, val json: String)

/**
 * A payload body that was decoded back into the DTO it was sent as.
 *
 * One case per wire type, which is what lets [RelayCodec.route] be an exhaustive
 * `when`: a new payload kind cannot be added without the compiler pointing at
 * every place that has to deal with it.
 */
internal sealed interface DecodedPayload {
    data class Chat(val message: ChatPayloadDto) : DecodedPayload
    data class ChatHistory(val history: ChatHistoryPayloadDto) : DecodedPayload
    data class ChatReceipt(val receipt: ChatReceiptPayloadDto) : DecodedPayload
    data class Beacon(val beacon: BeaconPayloadDto) : DecodedPayload
    data class InviteSecret(val secret: InviteSecretPayloadDto) : DecodedPayload
    data class BleIdentityKey(val identityKey: BleIdentityKeyPayloadDto) : DecodedPayload
    data class MemberDeparture(val departure: MemberDeparturePayloadDto) : DecodedPayload
    data class MeshPresence(val presence: MeshPresencePayloadDto) : DecodedPayload
    data class ChatColourClaim(val claim: ChatColourClaimPayloadDto) : DecodedPayload
    data class PeerRanging(val ranging: PeerRangingPayloadDto) : DecodedPayload
    data class MeshPeerAuthentication(val authentication: MeshLinkAuthenticationPayloadDto) : DecodedPayload
    data class JoinProof(val proof: JoinProofPayloadDto) : DecodedPayload
    data class EventControl(val control: EventControlPayloadDto) : DecodedPayload
    data class UwbAddress(val address: UwbPeerAddressDto) : DecodedPayload
    data class UwbParameters(val params: UwbControllerParametersDto) : DecodedPayload
    data class UwbOffer(val offer: UwbRangingOfferDto) : DecodedPayload
    data class MockRanging(val report: MockRangingDto) : DecodedPayload
}


/**
 * The one place that knows PingRing's wire format: how a DTO becomes a
 * [RelayEnvelope] body and how a body becomes a DTO again.
 *
 * Both transports need exactly the same answers to those two questions, and
 * before this existed each of them carried its own copy: two `when` blocks that
 * had to list every DTO, two id-to-type tables, and a `Moshi` instance per class.
 * That is a wire format with two authors, and a payload type added on one side
 * but not the other fails in the most confusing way there is - the message
 * simply never arrives, with nothing on either device saying why.
 *
 * Nothing here touches Android, so the format is covered by plain JVM tests (see
 * `RelayCodecTest`).
 */
internal object RelayCodec {

    const val CHAT = "chat"
    const val CHAT_HISTORY = "chat-history"
    const val CHAT_RECEIPT = "chat-receipt"
    const val BEACON = "beacon"
    const val INVITE_SECRET = "invite-secret"
    const val BLE_IDENTITY_KEY = "ble-identity-key"
    const val MEMBER_DEPARTURE = "member-departure"
    const val MESH_PRESENCE = "mesh-presence"
    const val PEER_RANGING = "peer-ranging"
    const val MESH_PEER_AUTHENTICATION = "mesh-peer-authentication"
    const val JOIN_PROOF = "join-proof"
    const val EVENT_CONTROL = "event-control"
    const val UWB_ADDRESS = "uwb-address"
    const val UWB_PARAMS = "uwb-params"
    const val UWB_OFFER = "uwb-offer"
    const val CHAT_COLOUR_CLAIM = "chat-colour-claim"

    /**
     * Debug-only: a scripted range report from tools/mock_radar.py.
     *
     * Only the LAN debug transport can receive one, because a real device has no
     * way of being told a distance it did not measure itself.
     */
    const val MOCK_RANGING = "mock-range"

    /** Every payload type this build understands inside an envelope. */
    val knownWireTypes: Set<String> = setOf(
        CHAT, CHAT_HISTORY, CHAT_RECEIPT, BEACON, INVITE_SECRET, BLE_IDENTITY_KEY, MEMBER_DEPARTURE, MESH_PRESENCE,
        MESH_PEER_AUTHENTICATION, JOIN_PROOF, EVENT_CONTROL, PEER_RANGING,
        UWB_ADDRESS, UWB_PARAMS, UWB_OFFER, MOCK_RANGING, CHAT_COLOUR_CLAIM
    )

    // One Moshi for the whole process, with the adapters resolved once. The
    // receive path used to call `moshi.adapter(X::class.java)` per payload - up
    // to eight lookups per message - on the transport's own IO thread; now it is
    // a field read.
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val envelopeAdapter: JsonAdapter<RelayEnvelope> = moshi.adapter(RelayEnvelope::class.java)
    private val chatAdapter: JsonAdapter<ChatPayloadDto> = moshi.adapter(ChatPayloadDto::class.java)
    private val chatHistoryAdapter: JsonAdapter<ChatHistoryPayloadDto> = moshi.adapter(ChatHistoryPayloadDto::class.java)
    private val chatReceiptAdapter: JsonAdapter<ChatReceiptPayloadDto> = moshi.adapter(ChatReceiptPayloadDto::class.java)
    private val beaconAdapter: JsonAdapter<BeaconPayloadDto> = moshi.adapter(BeaconPayloadDto::class.java)
    private val inviteSecretAdapter: JsonAdapter<InviteSecretPayloadDto> = moshi.adapter(InviteSecretPayloadDto::class.java)
    private val bleIdentityKeyAdapter: JsonAdapter<BleIdentityKeyPayloadDto> = moshi.adapter(BleIdentityKeyPayloadDto::class.java)
    private val memberDepartureAdapter: JsonAdapter<MemberDeparturePayloadDto> = moshi.adapter(MemberDeparturePayloadDto::class.java)
    private val meshPresenceAdapter: JsonAdapter<MeshPresencePayloadDto> = moshi.adapter(MeshPresencePayloadDto::class.java)
    private val peerRangingAdapter: JsonAdapter<PeerRangingPayloadDto> = moshi.adapter(PeerRangingPayloadDto::class.java)
    private val meshPeerAuthenticationAdapter: JsonAdapter<MeshLinkAuthenticationPayloadDto> = moshi.adapter(MeshLinkAuthenticationPayloadDto::class.java)
    private val joinProofAdapter: JsonAdapter<JoinProofPayloadDto> = moshi.adapter(JoinProofPayloadDto::class.java)
    private val eventControlAdapter: JsonAdapter<EventControlPayloadDto> = moshi.adapter(EventControlPayloadDto::class.java)
    private val uwbAddressAdapter: JsonAdapter<UwbPeerAddressDto> = moshi.adapter(UwbPeerAddressDto::class.java)
    private val uwbParamsAdapter: JsonAdapter<UwbControllerParametersDto> = moshi.adapter(UwbControllerParametersDto::class.java)
    private val uwbOfferAdapter: JsonAdapter<UwbRangingOfferDto> = moshi.adapter(UwbRangingOfferDto::class.java)
    private val mockRangingAdapter: JsonAdapter<MockRangingDto> = moshi.adapter(MockRangingDto::class.java)
    private val chatColourClaimAdapter: JsonAdapter<ChatColourClaimPayloadDto> =
        moshi.adapter(ChatColourClaimPayloadDto::class.java)

    fun encodeEnvelope(envelope: RelayEnvelope): String = envelopeAdapter.toJson(envelope)

    /**
     * Parses one relay envelope, or fails with the reason.
     *
     * A failure is returned rather than logged here because what to say about a
     * malformed line depends on the transport that read it.
     */
    fun decodeEnvelope(json: String): Result<RelayEnvelope> =
        runCatching { envelopeAdapter.fromJson(json) }
            .mapCatching { it ?: throw JsonDataException("the line held no envelope") }

    /** Wraps a DTO in the wire type name that identifies it, or null if it is not a payload at all. */
    /**
     * Decodes an enveloped payload body.
     *
     * `null` means the wire type is one this build has never heard of, which may
     * simply be a newer peer. A failed [Result] means the type is known but the
     * body would not decode, which is a bug on one of the two sides and worth
     * saying out loud: seen from the outside it looks exactly like a device that
     * never sent anything.
     */
    fun decode(wireType: String, json: String): Result<DecodedPayload>? = when (wireType) {
        CHAT -> decodeBody(chatAdapter, json, DecodedPayload::Chat)
        CHAT_HISTORY -> decodeBody(chatHistoryAdapter, json, DecodedPayload::ChatHistory)
        CHAT_RECEIPT -> decodeBody(chatReceiptAdapter, json, DecodedPayload::ChatReceipt)
        BEACON -> decodeBody(beaconAdapter, json, DecodedPayload::Beacon)
        INVITE_SECRET -> decodeBody(inviteSecretAdapter, json, DecodedPayload::InviteSecret)
        BLE_IDENTITY_KEY -> decodeBody(bleIdentityKeyAdapter, json, DecodedPayload::BleIdentityKey)
        MEMBER_DEPARTURE -> decodeBody(memberDepartureAdapter, json, DecodedPayload::MemberDeparture)
        MESH_PRESENCE -> decodeBody(meshPresenceAdapter, json, DecodedPayload::MeshPresence)
        CHAT_COLOUR_CLAIM -> decodeBody(chatColourClaimAdapter, json, DecodedPayload::ChatColourClaim)
        PEER_RANGING -> decodeBody(peerRangingAdapter, json, DecodedPayload::PeerRanging)
        MESH_PEER_AUTHENTICATION -> decodeBody(meshPeerAuthenticationAdapter, json, DecodedPayload::MeshPeerAuthentication)
        JOIN_PROOF -> decodeBody(joinProofAdapter, json, DecodedPayload::JoinProof)
        EVENT_CONTROL -> decodeBody(eventControlAdapter, json, DecodedPayload::EventControl)
        UWB_ADDRESS -> decodeBody(uwbAddressAdapter, json, DecodedPayload::UwbAddress)
        UWB_PARAMS -> decodeBody(uwbParamsAdapter, json, DecodedPayload::UwbParameters)
        UWB_OFFER -> decodeBody(uwbOfferAdapter, json, DecodedPayload::UwbOffer)
        MOCK_RANGING -> decodeBody(mockRangingAdapter, json, DecodedPayload::MockRanging)
        else -> null
    }

    /**
     * The last resort for a payload sent without its envelope wrapper, which is
     * how builds older than the envelope format wrote.
     *
     * Every field of a scripted range report is optional, so that one is tried
     * last and only counts when it names a peer: otherwise it could swallow the
     * payloads that own a `peerId` field, and a chat message would be read as a
     * range report for whoever it happened to name.
     */
    fun decodeUnwrapped(json: String): DecodedPayload? =
        decode(chatAdapter, json)?.takeIf { it.id.isNotEmpty() && it.text.isNotEmpty() }?.let(DecodedPayload::Chat)
            ?: decode(chatHistoryAdapter, json)?.let(DecodedPayload::ChatHistory)
            ?: decode(beaconAdapter, json)?.takeIf { it.id.isNotEmpty() }?.let(DecodedPayload::Beacon)
            ?: decode(uwbParamsAdapter, json)?.takeIf { it.hostPeerId.isNotEmpty() }?.let(DecodedPayload::UwbParameters)
            ?: decode(uwbAddressAdapter, json)?.takeIf { it.peerId.isNotEmpty() }?.let(DecodedPayload::UwbAddress)
            ?: decode(mockRangingAdapter, json)?.takeIf { it.peerId.isNotEmpty() }?.let(DecodedPayload::MockRanging)

    /**
     * Hands a decoded payload to the flow that carries its kind, and says whether
     * it was delivered.
     *
     * The two kinds that report false are the ones no single answer fits: a
     * ranging offer has to be relayed by a host and consumed by the guest it was
     * aimed at, and a scripted range report only exists on the LAN debug
     * transport at all. Both are left to the caller, which is the only place that
     * knows which of the two transports it is.
     */
    fun route(payload: DecodedPayload, sink: PayloadSink): Boolean = when (payload) {
        is DecodedPayload.Chat -> { sink.onChatMessage(payload.message); true }
        is DecodedPayload.ChatHistory -> { sink.onChatHistory(payload.history); true }
        is DecodedPayload.ChatReceipt -> { sink.onChatReceipt(payload.receipt); true }
        is DecodedPayload.Beacon -> { sink.onBeacon(payload.beacon); true }
        is DecodedPayload.InviteSecret -> { sink.onInviteSecret(payload.secret); true }
        is DecodedPayload.BleIdentityKey -> { sink.onBleIdentityKey(payload.identityKey); true }
        is DecodedPayload.MemberDeparture -> { sink.onMemberDeparture(payload.departure); true }
        is DecodedPayload.MeshPresence -> { sink.onMeshPresence(payload.presence); true }
        is DecodedPayload.PeerRanging -> { sink.onPeerRanging(payload.ranging); true }
        is DecodedPayload.ChatColourClaim -> { sink.onChatColourClaim(payload.claim); true }
        is DecodedPayload.MeshPeerAuthentication -> false
        is DecodedPayload.EventControl -> { sink.onEventControl(payload.control); true }
        is DecodedPayload.UwbAddress -> { sink.onUwbAddress(payload.address); true }
        is DecodedPayload.UwbParameters -> { sink.onUwbParameters(payload.params); true }
        is DecodedPayload.UwbOffer, is DecodedPayload.JoinProof, is DecodedPayload.MockRanging -> false
    }

    private fun <T : Any> decode(adapter: JsonAdapter<T>, json: String): T? =
        runCatching { adapter.fromJson(json) }.getOrNull()

    private fun <T : Any> decodeBody(
        adapter: JsonAdapter<T>,
        json: String,
        wrap: (T) -> DecodedPayload
    ): Result<DecodedPayload> =
        runCatching { adapter.fromJson(json) ?: throw JsonDataException("the body decoded to nothing") }.map(wrap)

    fun encode(params: Any): EncodedPayload? = when (params) {
        is ChatPayloadDto -> EncodedPayload(CHAT, chatAdapter.toJson(params))
        is ChatHistoryPayloadDto -> EncodedPayload(CHAT_HISTORY, chatHistoryAdapter.toJson(params))
        is ChatReceiptPayloadDto -> EncodedPayload(CHAT_RECEIPT, chatReceiptAdapter.toJson(params))
        is BeaconPayloadDto -> EncodedPayload(BEACON, beaconAdapter.toJson(params))
        is InviteSecretPayloadDto -> EncodedPayload(INVITE_SECRET, inviteSecretAdapter.toJson(params))
        is BleIdentityKeyPayloadDto -> EncodedPayload(BLE_IDENTITY_KEY, bleIdentityKeyAdapter.toJson(params))
        is MemberDeparturePayloadDto -> EncodedPayload(MEMBER_DEPARTURE, memberDepartureAdapter.toJson(params))
        is MeshPresencePayloadDto -> EncodedPayload(MESH_PRESENCE, meshPresenceAdapter.toJson(params))
        is PeerRangingPayloadDto -> EncodedPayload(PEER_RANGING, peerRangingAdapter.toJson(params))
        is MeshLinkAuthenticationPayloadDto -> EncodedPayload(MESH_PEER_AUTHENTICATION, meshPeerAuthenticationAdapter.toJson(params))
        is JoinProofPayloadDto -> EncodedPayload(JOIN_PROOF, joinProofAdapter.toJson(params))
        is EventControlPayloadDto -> EncodedPayload(EVENT_CONTROL, eventControlAdapter.toJson(params))
        is UwbPeerAddressDto -> EncodedPayload(UWB_ADDRESS, uwbAddressAdapter.toJson(params))
        is UwbControllerParametersDto -> EncodedPayload(UWB_PARAMS, uwbParamsAdapter.toJson(params))
        is UwbRangingOfferDto -> EncodedPayload(UWB_OFFER, uwbOfferAdapter.toJson(params))
        is ChatColourClaimPayloadDto -> EncodedPayload(CHAT_COLOUR_CLAIM, chatColourClaimAdapter.toJson(params))
        else -> null
    }
}
