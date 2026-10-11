package com.fkurevija.pingring.ui.navigation

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.session.Event
import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.session.SessionState
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import com.fkurevija.pingring.ui.screens.HomeScreen
import com.fkurevija.pingring.ui.screens.HostSetupScreen
import com.fkurevija.pingring.ui.screens.PingRingJoinScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun HomeDestination(
    sessionState: SessionState,
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    backStack: NavBackStack<NavKey>,
    reduceMotionEnabled: Boolean,
) {
    val currentEvent = sessionState.currentEvent
    if (currentEvent != null) {
        LaunchedEffect(currentEvent.id, sessionState.isHost) {
            val targetKey = if (sessionState.isHost) {
                HostSetupKey(currentEvent.id)
            } else {
                RadarKey(currentEvent.id)
            }
            if (backStack.lastOrNull() != targetKey) {
                backStack.resetTo(targetKey)
            }
        }
    }

    HomeScreen(
        reduceMotionEnabled = reduceMotionEnabled,
        onHost = {
            if (sessionManager.sessionState.value.currentEvent == null) {
                val eventCode = generateEventCode()
                sessionManager.hostEvent(
                    Event.create(
                        id = eventCode,
                        hostPeerId = sessionManager.peerId.value,
                        useUwb = discoveryRepository.isUwbSupported(),
                        inviteSecret = InviteSecret.generate(),
                    )
                )
                discoveryRepository.announcePingRingCreated()
                backStack.add(HostSetupKey(eventCode))
            }
        },
        onJoin = { backStack.add(PingRingJoinKey) },
        onSettings = { backStack.add(AppSettingsKey) }
    )
}

@Composable
internal fun HostSetupDestination(
    key: HostSetupKey,
    sessionState: SessionState,
    savedUsername: String,
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    appearanceSettings: AppearanceSettingsRepository,
    backStack: NavBackStack<NavKey>,
    pendingJoinRequests: List<PendingJoinRequest>,
) {
    val event = sessionState.currentEvent
    if (event == null || event.id != key.eventCode || !sessionState.isHost) {
        LaunchedEffect(key) {
            if (backStack.lastOrNull() != HomeKey) {
                backStack.resetTo(HomeKey)
            }
        }
    } else {
        HostSetupScreen(
            eventCode = key.eventCode,
            inviteSecret = event.inviteSecret,
            savedUsername = savedUsername,
            discoveryRepository = discoveryRepository,
            existingMemberNames = sessionState.friends.map { it.name },
            onEndEvent = {
                discoveryRepository.announceEventClosure(event)
                discoveryRepository.stopDiscovery()
                sessionManager.endHostedEvent()
                backStack.resetTo(HomeKey)
            },
            onSaveUsername = { username ->
                appearanceSettings.setUserName(username)
                sessionManager.setUserName(username)
            },
            onView = {
                backStack.resetTo(RadarKey(key.eventCode))
            },
            onOpenSettings = {
                backStack.add(PingRingSettingsKey(key.eventCode))
            },
            pendingJoinRequests = pendingJoinRequests,
            onApproveJoinRequest = discoveryRepository::approveJoinRequest,
            onDenyJoinRequest = discoveryRepository::denyJoinRequest,
        )
    }
}

@Composable
internal fun JoinDestination(
    savedUsername: String,
    sessionState: SessionState,
    sessionManager: SessionManager,
    discoveryRepository: DiscoveryRepository,
    appearanceSettings: AppearanceSettingsRepository,
    backStack: NavBackStack<NavKey>,
    joinWaitTimeoutMillis: Long,
) {
    val context = LocalContext.current
    var isConnecting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var joinJob by remember { mutableStateOf<Job?>(null) }

    fun cancelJoin() {
        joinJob?.cancel()
        joinJob = null
        sessionManager.leaveEvent()
        isConnecting = false
    }

    LaunchedEffect(Unit) {
        discoveryRepository.eventClosed.collect {
            if (isConnecting) {
                cancelJoin()
                Toast.makeText(
                    context,
                    context.getString(R.string.join_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    PingRingJoinScreen(
        savedUsername = savedUsername,
        existingMembers = sessionState.friends.map { it.name },
        isConnecting = isConnecting,
        onBack = {
            cancelJoin()
            backStack.resetTo(HomeKey)
        },
        onJoinEvent = { eventCode, username, inviteSecret ->
            if (!isConnecting) {
                isConnecting = true
                appearanceSettings.setUserName(username)
                sessionManager.setUserName(username)
                val event = Event.create(id = eventCode, inviteSecret = inviteSecret)
                if (sessionManager.joinEvent(event)) {
                    joinJob = coroutineScope.launch {
                        val joinWaitMillis = joinWaitTimeoutMillis
                        Log.i(
                            "PingRingJoin",
                            "Joining $eventCode, giving up after ${joinWaitMillis}ms",
                        )
                        val connected = sessionManager.awaitConnectedPeer(joinWaitMillis)

                        if (connected) {
                            isConnecting = false
                            backStack.resetTo(RadarKey(eventCode))
                        } else {
                            Log.w("PingRingJoin", "Join timeout or failure for event $eventCode: host not found or unavailable.")
                            sessionManager.leaveEvent()
                            Toast.makeText(
                                context,
                                context.getString(R.string.join_failed),
                                Toast.LENGTH_LONG
                            ).show()
                            isConnecting = false
                        }
                    }
                } else {
                    isConnecting = false
                }
            }
        }
    )
}

private fun generateEventCode(): String =
    UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
