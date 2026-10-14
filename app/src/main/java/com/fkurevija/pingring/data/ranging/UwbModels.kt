package com.fkurevija.pingring.data.ranging
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.NearbyTransport

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
 * One guest's UWB ranging offer, routed through the connected member mesh to
 * the guest it names.
 *
 * UWB needs both ends of a session to agree on the channel, the STS key and the
 * session id before a single ranging exchange can happen. A guest can route
 * this offer over any authorized mesh path; the addressed guest becomes the
 * controlee using the exact channel and STS key sent here.
 *
 * [targetPeerId] is the guest being offered to, so mesh peers forward to exactly
 * one peer instead of broadcasting. [fromPeerId] is the guest that will
 * act as UWB controller, and [controllerAddress] is its own radio address, which
 * the controlee needs in order to address the very first ranging message back.
 *
 * The STS key is visible to each admitted relay on the route, including the
 * host if it is on that route. Admitted relays are trusted group members; this
 * payload is not end-to-end encrypted between the two ranging guests.
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
 * Debug-only synthetic measurement pushed by tools/mock_radar.py through the
 * LAN debug relay. It can model BLE RSSI, Wi-Fi RTT distance, or UWB-shaped
 * distance/bearing reports at the app's radar input boundary.
 *
 * The emulator has neither a UWB nor a BLE radio, so without this the radar
 * has no source for range at all and nothing but the chat/beacon flows can be
 * exercised remotely. [NearbyTransport.receivedMockRanging] carries these and
 * [DiscoveryRepository] applies them through the very same update the real
 * ranging path performs, so what is being tested is the production rendering
 * code and not a mock of it.
 *
 * Measurement units match the corresponding Android results: metres, degrees,
 * dBm, and metres of RTT uncertainty. The payload is synthetic and does not
 * exercise any Android radio API. [name] is optional: when set it renames the
 * peer label, which is how two scripted peers can be distinguished.
 */
@JsonClass(generateAdapter = true)
data class MockRangingDto(
    val peerId: String,
    val distance: Float? = null,
    val azimuth: Float? = null,
    val elevation: Float? = null,
    val name: String? = null,
    val source: MockRangingSource = MockRangingSource.UWB,
    val accuracyMeters: Float? = null,
    /**
     * A raw signal strength in dBm, for driving the RSSI path instead of
     * pretending to be a ranging fix.
     *
     * BLE reports carry [rssi] without [distance] and go through
     * [RssiKalmanFilter]. UWB and Wi-Fi RTT reports carry [distance], while
     * only UWB reports include a bearing. The synthetic source selects the
     * corresponding production radar update path.
     */
    val rssi: Int? = null
)

enum class MockRangingSource {
    @Json(name = "UWB")
    UWB,

    @Json(name = "WIFI_RTT")
    WIFI_RTT,

    @Json(name = "BLE_RSSI")
    BLE_RSSI,
}
