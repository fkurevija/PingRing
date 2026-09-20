package com.fkurevija.pingring.data

import com.fkurevija.pingring.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

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

@JsonClass(generateAdapter = true)
data class UwbExchangeResponseDto(
    val controller: UwbControllerParametersDto?,
    val peers: List<UwbPeerAddressDto>
)

private interface UwbExchangeApi {
    @GET("api/events/{eventId}/uwb")
    suspend fun get(@Path("eventId") eventId: String): UwbExchangeResponseDto

    @POST("api/events/{eventId}/uwb/controller")
    suspend fun publishController(
        @Path("eventId") eventId: String,
        @Body parameters: UwbControllerParametersDto
    )

    @POST("api/events/{eventId}/uwb/peers")
    suspend fun publishPeer(@Path("eventId") eventId: String, @Body peer: UwbPeerAddressDto)
}

class UwbSessionExchange {
    private val api = Retrofit.Builder()
        .baseUrl(BuildConfig.COORDINATION_URL)
        .addConverterFactory(
            MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        )
        .build()
        .create(UwbExchangeApi::class.java)

    suspend fun publishController(eventId: String, parameters: UwbControllerParametersDto) =
        api.publishController(eventId, parameters)

    suspend fun publishPeerAddress(eventId: String, peer: UwbPeerAddressDto) =
        api.publishPeer(eventId, peer)

    suspend fun get(eventId: String) = api.get(eventId)
}
