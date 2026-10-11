package com.fkurevija.pingring.data.ranging

import com.fkurevija.pingring.data.session.MAX_PARTICIPANTS
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.radar.FocusPolicy
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.session.SessionState

/** Owns UWB sessions, peer addresses, and the short transport-drop retention window. */
internal class UwbRangingCoordinator(
    context: Context,
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val repositoryScope: () -> CoroutineScope?,
    private val refreshFocus: () -> Unit,
    private val connectedPeerIds: () -> Set<String>,
    private val onUwbPosition: (String, RangingResult) -> Unit,
) {
    private val tag = "DiscoveryRepository"
    private val manager: UwbManager? = if (context.packageManager.hasSystemFeature("android.hardware.uwb")) {
        try {
            UwbManager.createInstance(context)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize UWB Manager", e)
            null
        }
    } else {
        null
    }

    private var sessionJob: Job? = null
    private val activeRangingJobs = mutableMapOf<String, Job>()
    private var activeFocusRangingJob: Job? = null
    private var activeFocusRangingTarget: String? = null
    private val peerUwbAddresses = mutableMapOf<String, String>()
    private val retainedPeerTimestamps = mutableMapOf<String, Long>()
    private val lastUwbMeasurementAt = mutableMapOf<String, Long>()
    private val uwbRangedPeers = mutableSetOf<String>()

    val activeFocusTarget: String?
        get() = activeFocusRangingTarget

    val retainedPeerIds: Set<String>
        get() = retainedPeerTimestamps.keys.toSet()

    fun hasUwbMeasurement(peerId: String): Boolean = peerId in uwbRangedPeers

    fun markUwbMeasurement(peerId: String) {
        uwbRangedPeers.add(peerId)
    }

    fun recordUwbMeasurement(peerId: String, measuredAtMillis: Long) {
        uwbRangedPeers.add(peerId)
        lastUwbMeasurementAt[peerId] = measuredAtMillis
    }

    fun peerAddress(peerId: String): String? = peerUwbAddresses[peerId]

    fun forgetPeerAddress(peerId: String) {
        peerUwbAddresses.remove(peerId)
    }

    fun retainPeer(peerId: String, timestampMillis: Long) {
        retainedPeerTimestamps[peerId] = timestampMillis
    }

    fun releaseRetainedPeer(peerId: String): Boolean = retainedPeerTimestamps.remove(peerId) != null

    fun expiredRetainedPeerIds(nowMillis: Long, retentionWindowMillis: Long): List<String> =
        retainedPeerTimestamps.filterValues {
            !UwbRetentionPolicy.isWithinRetentionWindow(it, nowMillis, retentionWindowMillis)
        }.keys.toList()

    fun forgetPeer(peerId: String) {
        uwbRangedPeers.remove(peerId)
        lastUwbMeasurementAt.remove(peerId)
        retainedPeerTimestamps.remove(peerId)
        peerUwbAddresses.remove(peerId)
    }

    fun start() {
        val currentManager = manager
        val scope = repositoryScope() ?: return
        val event = sessionManager.sessionState.value.currentEvent ?: return
        val localPeerId = sessionManager.peerId.value

        sessionJob = scope.launch {
            if (currentManager == null || !event.useUwb) {
                Log.d(tag, "UWB ranging disabled or unavailable")
                return@launch
            }
            try {
                if (sessionManager.sessionState.value.isHost) {
                    val controller = currentManager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val parameters = UwbControllerParametersDto(
                        hostPeerId = localPeerId,
                        controllerAddress = controller.localAddress.toString(),
                        channel = controller.uwbComplexChannel.channel,
                        preambleIndex = controller.uwbComplexChannel.preambleIndex,
                        sessionId = event.id.hashCode(),
                        staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    )

                    transport.receivedPeerAddress.collect { peer ->
                        peerUwbAddresses[peer.peerId] = peer.address
                        refreshFocus()

                        if (activeRangingJobs[peer.peerId]?.isActive == true) return@collect

                        activeRangingJobs[peer.peerId] = launch {
                            val startedAt = System.currentTimeMillis()
                            try {
                                transport.sendUwbParameters(peer.peerId, parameters)
                                Log.i(
                                    tag,
                                    "Starting UWB session for peer ${peer.peerId} " +
                                        "(active sessions: ${activeRangingJobs.size}/${MAX_PARTICIPANTS - 1})"
                                )
                                controller.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        parameters.sessionId,
                                        0,
                                        sessionKey,
                                        null,
                                        null,
                                        listOf(UwbDevice.createForAddress(peer.address)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> onUwbPosition(peer.peerId, result) }
                            } catch (e: Exception) {
                                Log.e(
                                    tag,
                                    "UWB session failed for peer ${peer.peerId} " +
                                        "after ${System.currentTimeMillis() - startedAt} ms; " +
                                        "this peer will fall back to RSSI-only distance",
                                    e
                                )
                            } finally {
                                activeRangingJobs.remove(peer.peerId)
                            }
                        }
                    }
                } else {
                    val controlee = currentManager.controleeSessionScope()
                    launch {
                        while (isActive) {
                            transport.broadcastUwbParameters(
                                UwbPeerAddressDto(localPeerId, controlee.localAddress.toString())
                            )
                            delay(5000)
                        }
                    }

                    transport.receivedControllerParams.collect { controllerParams ->
                        if (activeRangingJobs[controllerParams.hostPeerId]?.isActive == true) return@collect

                        activeRangingJobs[controllerParams.hostPeerId] = launch {
                            try {
                                controlee.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        controllerParams.sessionId,
                                        0,
                                        Base64.decode(controllerParams.staticStsKey, Base64.NO_WRAP),
                                        null,
                                        UwbComplexChannel(controllerParams.channel, controllerParams.preambleIndex),
                                        listOf(UwbDevice.createForAddress(controllerParams.controllerAddress)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> onUwbPosition(controllerParams.hostPeerId, result) }
                            } catch (e: Exception) {
                                Log.e(tag, "UWB session failed with host", e)
                            } finally {
                                activeRangingJobs.remove(controllerParams.hostPeerId)
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w(tag, "UWB ranging unavailable; continuing with BLE proximity", exception)
            }
        }
    }

    fun restart() {
        sessionJob?.cancel()
        sessionJob = null
        activeRangingJobs.values.forEach { it.cancel() }
        activeRangingJobs.clear()
        start()
    }

    fun onFocusChanged(focusedPeerId: String?) {
        val currentManager = manager ?: return
        val scope = repositoryScope() ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return

        val target = FocusPolicy.rangingTarget(
            focusPeerId = focusedPeerId,
            hostPeerId = event.hostPeerId,
            connectedPeerIds = connectedPeerIds() + retainedPeerTimestamps.keys,
        )
        val targetId = (target as? FocusPolicy.RangingTarget.Peer)?.peerId
        if (activeFocusRangingJob?.isActive == true && targetId == activeFocusRangingTarget) return
        activeFocusRangingJob?.cancel()
        activeFocusRangingTarget = targetId
        if (targetId == null) {
            Log.i(tag, "UWB ranging idle: no focused member to range")
            return
        }

        val address = peerUwbAddresses[targetId]
        if (address == null) {
            Log.i(tag, "UWB ranging idle: $targetId has not shared a UWB address yet")
            return
        }

        val isHost = (target as FocusPolicy.RangingTarget.Peer).isHost
        activeFocusRangingJob = scope.launch {
            delay(UWB_FOCUS_SETTLE_MILLIS)
            try {
                Log.i(tag, "UWB: ranging $targetId${if (isHost) " (host)" else ""} at $address")
                val eventId = sessionManager.sessionState.value.currentEvent?.id ?: return@launch
                val sessionId = eventId.hashCode()
                if (isHost) {
                    val controller = currentManager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    transport.sendUwbParameters(
                        targetId,
                        UwbControllerParametersDto(
                            hostPeerId = sessionManager.peerId.value,
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = sessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            sessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> onUwbPosition(targetId, result) }
                } else {
                    val controller = currentManager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val currentEventId = sessionManager.sessionState.value.currentEvent?.id
                    if (currentEventId == null) {
                        Log.w(tag, "UWB: no event to derive a session id from; skipping $targetId")
                        return@launch
                    }
                    val guestSessionId = (31L * currentEventId.hashCode() +
                        17L * (targetId.hashCode().toLong() + sessionManager.peerId.value.hashCode())).toInt()
                    transport.sendUwbRangingOfferToHost(
                        UwbRangingOfferDto(
                            fromPeerId = sessionManager.peerId.value,
                            targetPeerId = targetId,
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = guestSessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            guestSessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> onUwbPosition(targetId, result) }
                }
            } catch (e: Exception) {
                Log.e(tag, "UWB focus session with $targetId failed; peer falls back to RSSI", e)
            }
        }
    }

    fun acceptIncomingRangingOffer(offer: UwbRangingOfferDto) {
        val currentManager = manager ?: return
        val scope = repositoryScope() ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return
        val localPeerId = sessionManager.peerId.value
        if (offer.targetPeerId != localPeerId || offer.fromPeerId == localPeerId) return
        if (offer.fromPeerId != event.hostPeerId && offer.fromPeerId !in connectedPeerIds()) {
            Log.w(tag, "Ignoring a UWB ranging offer from ${offer.fromPeerId}: not a member of this event")
            return
        }

        Log.i(
            tag,
            "Accepting a UWB ranging offer from ${offer.fromPeerId} " +
                "(session ${offer.sessionId}, channel ${offer.channel})"
        )
        scope.launch {
            try {
                val controlee = currentManager.controleeSessionScope()
                controlee.prepareSession(
                    RangingParameters(
                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                        offer.sessionId,
                        0,
                        Base64.decode(offer.staticStsKey, Base64.NO_WRAP),
                        null,
                        UwbComplexChannel(offer.channel, offer.preambleIndex),
                        listOf(UwbDevice.createForAddress(offer.controllerAddress)),
                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                    )
                ).collect { result -> onUwbPosition(offer.fromPeerId, result) }
            } catch (e: Exception) {
                Log.e(tag, "UWB ranging as controlee for ${offer.fromPeerId} failed", e)
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        activeRangingJobs.values.forEach { it.cancel() }
        activeRangingJobs.clear()
        activeFocusRangingJob?.cancel()
        activeFocusRangingJob = null
        activeFocusRangingTarget = null
        peerUwbAddresses.clear()
        retainedPeerTimestamps.clear()
        lastUwbMeasurementAt.clear()
        uwbRangedPeers.clear()
    }

    private companion object {
        const val UWB_FOCUS_SETTLE_MILLIS = 750L
    }
}
