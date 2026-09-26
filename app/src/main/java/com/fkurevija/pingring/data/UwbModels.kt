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
    val name: String? = null
)
