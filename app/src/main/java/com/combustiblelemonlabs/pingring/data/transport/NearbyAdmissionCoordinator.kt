package com.combustiblelemonlabs.pingring.data.transport

import android.os.Handler
import android.os.Looper
import com.combustiblelemonlabs.pingring.data.ConnectionAuthenticationRequest
import com.combustiblelemonlabs.pingring.data.JOIN_REQUEST_TIMEOUT_MILLIS
import com.combustiblelemonlabs.pingring.data.NearbyConnectionsManager
import com.combustiblelemonlabs.pingring.data.PendingJoinRequest
import com.combustiblelemonlabs.pingring.data.SAS_PROMPT_WINDOW_MILLIS
import com.combustiblelemonlabs.pingring.data.expiredAuthenticationRequests
import com.combustiblelemonlabs.pingring.data.isWithinSasPromptRateLimit
import com.combustiblelemonlabs.pingring.data.sweepExpiredJoinRequests as calculateExpiredJoinRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.combustiblelemonlabs.pingring.data.session.Event

/**
 * Owns the queues and expiry timers that gate Nearby connection admission.
 *
 * Radio-specific actions stay with [NearbyConnectionsManager]; callbacks keep
 * endpoint refusal and transport-event publication at that boundary.
 */
internal class NearbyAdmissionCoordinator(
    private val onPendingAuthenticationRemoved: (String) -> Unit,
    private val onPendingAuthenticationsCleared: () -> Unit,
    private val onAuthenticationExpired: (ConnectionAuthenticationRequest) -> Unit,
    private val onJoinRequestsExpired: (List<PendingJoinRequest>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val _pendingJoinRequests = MutableStateFlow<List<PendingJoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<PendingJoinRequest>> = _pendingJoinRequests.asStateFlow()
    private val authenticationRequests = linkedMapOf<String, ConnectionAuthenticationRequest>()
    private val sasPromptTimes = mutableListOf<Long>()
    private var joinSweepRunnable: Runnable? = null
    private var authenticationSweepRunnable: Runnable? = null

    val authenticationRequestValues: Collection<ConnectionAuthenticationRequest>
        get() = authenticationRequests.values

    fun authenticationRequest(endpointId: String): ConnectionAuthenticationRequest? =
        authenticationRequests[endpointId]

    fun mayPromptForAuthentication(endpointId: String, nowMillis: Long): Boolean {
        sasPromptTimes.removeAll { nowMillis - it >= SAS_PROMPT_WINDOW_MILLIS }
        return isWithinSasPromptRateLimit(sasPromptTimes, nowMillis) &&
            (authenticationRequests.size < MAX_PENDING_AUTHENTICATIONS ||
                endpointId in authenticationRequests)
    }

    fun recordAuthenticationPrompt(request: ConnectionAuthenticationRequest, nowMillis: Long) {
        sasPromptTimes += nowMillis
        authenticationRequests[request.endpointId] = request
    }

    fun removeAuthenticationRequest(endpointId: String) {
        authenticationRequests.remove(endpointId)
    }

    fun scheduleAuthenticationSweep() {
        authenticationSweepRunnable?.let(handler::removeCallbacks)
        authenticationSweepRunnable = null
        val oldest = authenticationRequests.values.minOfOrNull { it.requestedAtMillis } ?: return
        val delayMillis =
            (oldest + JOIN_REQUEST_TIMEOUT_MILLIS + 1L - System.currentTimeMillis()).coerceAtLeast(0L)
        authenticationSweepRunnable = Runnable { expireAuthenticationRequests() }.also { runnable ->
            handler.postDelayed(runnable, delayMillis)
        }
    }

    private fun expireAuthenticationRequests() {
        authenticationSweepRunnable = null
        val expired = expiredAuthenticationRequests(authenticationRequests.values, System.currentTimeMillis())
        expired.forEach { request ->
            authenticationRequests.remove(request.endpointId)
            onPendingAuthenticationRemoved(request.endpointId)
            onAuthenticationExpired(request)
        }
        scheduleAuthenticationSweep()
    }

    fun pendingJoinRequest(endpointId: String): PendingJoinRequest? =
        _pendingJoinRequests.value.firstOrNull { it.endpointId == endpointId }

    fun containsPendingJoinRequest(endpointId: String): Boolean =
        pendingJoinRequest(endpointId) != null

    fun addPendingJoinRequest(request: PendingJoinRequest) {
        _pendingJoinRequests.update { current ->
            (current.filterNot { it.endpointId == request.endpointId } + request)
                .sortedBy { it.requestedAtMillis }
        }
    }

    /** Removes a user-resolved request; the caller rearms the sweep after its endpoint action. */
    fun takePendingJoinRequest(endpointId: String): PendingJoinRequest? {
        val request = pendingJoinRequest(endpointId) ?: return null
        cancelJoinRequestSweep()
        _pendingJoinRequests.update { current -> current.filterNot { it.endpointId == endpointId } }
        return request
    }

    fun scheduleJoinRequestSweep() {
        cancelJoinRequestSweep()
        val sweep = calculateExpiredJoinRequests(_pendingJoinRequests.value, System.currentTimeMillis())
        val delay = sweep.nextCheckInMillis ?: return
        joinSweepRunnable = Runnable { expireJoinRequests() }.also { runnable ->
            handler.postDelayed(runnable, delay)
        }
    }

    private fun expireJoinRequests() {
        val sweep = calculateExpiredJoinRequests(_pendingJoinRequests.value, System.currentTimeMillis())
        if (sweep.expired.isNotEmpty()) {
            val expiredEndpoints = sweep.expired.map { it.endpointId }.toSet()
            _pendingJoinRequests.update { current ->
                current.filterNot { it.endpointId in expiredEndpoints }
            }
            onJoinRequestsExpired(sweep.expired)
        }
        scheduleJoinRequestSweep()
    }

    private fun cancelJoinRequestSweep() {
        joinSweepRunnable?.let(handler::removeCallbacks)
        joinSweepRunnable = null
    }

    fun clear() {
        _pendingJoinRequests.value = emptyList()
        authenticationRequests.clear()
        onPendingAuthenticationsCleared()
        cancelJoinRequestSweep()
        authenticationSweepRunnable?.let(handler::removeCallbacks)
        authenticationSweepRunnable = null
    }

    private companion object {
        const val MAX_PENDING_AUTHENTICATIONS = 8
    }
}
