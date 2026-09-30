package com.fkurevija.pingring.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UwbControllerParametersDto(
    val hostPeerId: String,
    val controllerAddress: String,
    val channel: Int,
    val preambleIndex: Int,
    val sessionId: Int,
    val staticStsKey: String
)

@JsonClass(generateAdapter = true)
data class UwbPeerAddressDto(val peerId: String, val address: String)

/**
 * One guest's UWB ranging offer, handed to the host to pass on to a second
 * guest.
 *
 * UWB needs both ends of a session to agree on the channel, the STS key and the
 * session id before a single ranging exchange can happen. That works fine for
 * host-to-member, because the host is the only one that has to invent them and
 * it can send the result to everyone. It does not work for guest-to-guest: the
 * transport is a P2P_STAR, so two guests have no connection between them and
 * no way to exchange anything directly. A guest that wants to range another
 * guest therefore has to route this offer through the host, which is the only
 * peer it is actually connected to.
 *
 * [targetPeerId] is the guest being offered to, so the host can forward this to
 * exactly one peer instead of broadcasting. [fromPeerId] is the guest that will
 * act as UWB controller, and [controllerAddress] is its own radio address, which
 * the controlee needs in order to address the very first ranging message back.
 *
 * The STS key here is the same trust model as a relayed chat message: it travels
 * over the encrypted connection to the host, which relays it verbatim. It is not
 * end-to-end between the two guests, which is worth being explicit about - a
 * host in a PingRing already brokers and re-broadcasts every message, so it is
 * already fully trusted by the members who joined it.
 */
@JsonClass(generateAdapter = true)
data class UwbRangingOfferDto(
    val fromPeerId: String,
    val targetPeerId: String,
    val controllerAddress: String,
    val channel: Int,
    val preambleIndex: Int,
    val sessionId: Int,
    val staticStsKey: String,
)

/**
 * Debug-only stand-in for a UWB ranging report, pushed into the app by
 * tools/mock_radar.py through the LAN debug relay so that distance/azimuth can
 * be scripted from the development machine.
 *
 * The emulator has neither a UWB nor a BLE radio, so without this the radar
 * has no source for range at all and nothing but the chat/beacon flows can be
 * exercised remotely. [NearbyTransport.receivedMockRanging] carries these and
 * [DiscoveryRepository] applies them through the very same update the real
 * ranging path performs, so what is being tested is the production rendering
 * code and not a mock of it.
 *
 * The field names match the real report (`RangingResult.RangingResultPosition`
 * has distance/azimuth/elevation too) and the units are the same: metres and
 * degrees. [name] is optional: when set it also renames the pin, which is how
 * you can tell two scripted peers apart on the radar.
 */
@JsonClass(generateAdapter = true)
data class MockRangingDto(
    val peerId: String,
    val distance: Float? = null,
    val azimuth: Float? = null,
    val elevation: Float? = null,
    val name: String? = null,
    /**
     * A raw signal strength in dBm, for driving the RSSI path instead of
     * pretending to be a ranging fix.
     *
     * A report carrying [distance] is treated as a UWB-style measurement: it is
     * precise by definition, so it wins over any signal-derived estimate. That
     * makes it useless for testing the *smoothing* of signal readings, because
     * a scripted distance is never noisy - it goes on screen exactly as sent.
     * A report with [rssi] and no [distance] is the BLE case instead: the
     * reading is fed through [RssiSmoother] like a real advert, so noise,
     * outliers and dropouts can be exercised against the real filter.
     */
    val rssi: Int? = null
)
