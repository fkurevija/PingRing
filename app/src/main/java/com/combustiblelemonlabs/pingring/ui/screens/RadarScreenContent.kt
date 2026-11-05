package com.combustiblelemonlabs.pingring.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.BeaconAlertSettings
import com.combustiblelemonlabs.pingring.data.chat.ChatBubbleColorPolicy
import com.combustiblelemonlabs.pingring.data.chat.ChatTimelinePolicy
import com.combustiblelemonlabs.pingring.data.ConnectionRecoveryPromptPolicy
import com.combustiblelemonlabs.pingring.data.discovery.ConnectivityStatus
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.radar.FocusPolicy
import com.combustiblelemonlabs.pingring.data.radar.LastKnownPeerDirection
import com.combustiblelemonlabs.pingring.data.PendingJoinRequest
import com.combustiblelemonlabs.pingring.data.radar.RadarAccessibilityProbe
import com.combustiblelemonlabs.pingring.data.radar.RadarDirectionPolicy
import com.combustiblelemonlabs.pingring.data.radar.RadarFeedbackPolicy
import com.combustiblelemonlabs.pingring.data.radar.RadarPeerUpdate
import com.combustiblelemonlabs.pingring.data.ranging.RangingSourcePolicy
import com.combustiblelemonlabs.pingring.data.session.SessionState
import com.combustiblelemonlabs.pingring.ui.accessibility.rememberAccessibilityEnvironment
import kotlinx.coroutines.delay
import kotlin.math.atan2
import com.combustiblelemonlabs.pingring.data.chat.ChatTimelineItem
import com.combustiblelemonlabs.pingring.data.radar.RadarAnnouncementPolicy
import com.combustiblelemonlabs.pingring.data.session.MAX_PARTICIPANTS
import com.combustiblelemonlabs.pingring.data.session.Event

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreenContent(
    eventId: String,
    isHost: Boolean,
    coordinationError: String?,
    discoveryRepository: DiscoveryRepository?,
    sessionState: SessionState,
    chatBubbleColorArgb: Long?,
    onChatBubbleColorChange: (Long) -> Unit,
    chatColourHoldersOverride: Map<Long, String>? = null,
    refusedChatColourArgb: Long? = null,
    localPeerIdOverride: String? = null,
    radarScreenReaderSummaryEnabled: Boolean,
    connectionRecoveryPromptEnabled: Boolean = true,
    connectionRecoveryPromptDelayMillis: Long = 120_000L,
    hostConnectionLostAtMillis: Long? = null,
    lastKnownPeerDirections: List<LastKnownPeerDirection> = emptyList(),
    isTopDestination: Boolean = true,
    reduceMotionEnabled: Boolean = false,
    proximityHapticsEnabled: Boolean = true,
    spokenRadarUpdatesEnabled: Boolean = false,
    pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
    onApproveJoinRequest: (String) -> Unit = {},
    onDenyJoinRequest: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    readReceiptsEnabled: Boolean = true,
    onReadReceiptsChanged: (Boolean) -> Unit = {},
    onEventSettings: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit,
) {
    var showEndConfirmation by remember { mutableStateOf(false) }
    var showBeaconSheet by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<Long?>(null) }
    var showQrCode by remember { mutableStateOf(false) }
    var showMemberSheet by remember { mutableStateOf(false) }
    var showConnectionLostDialog by remember(eventId) { mutableStateOf(false) }
    var connectionLostSnoozedUntilMillis by rememberSaveable(eventId) { mutableLongStateOf(0L) }
    var connectionPromptSilenced by rememberSaveable(eventId) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activityResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, lifecycleEvent ->
            when (lifecycleEvent) {
                Lifecycle.Event.ON_RESUME -> activityResumed = true
                Lifecycle.Event.ON_PAUSE -> activityResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { showEndConfirmation = true }
    LaunchedEffect(Unit) { focusManager.clearFocus() }
    LaunchedEffect(discoveryRepository) { discoveryRepository?.startDiscovery() }

    LaunchedEffect(
        eventId,
        isHost,
        isTopDestination,
        activityResumed,
        hostConnectionLostAtMillis,
        connectionRecoveryPromptEnabled,
        connectionRecoveryPromptDelayMillis,
        connectionLostSnoozedUntilMillis,
        connectionPromptSilenced,
    ) {
        showConnectionLostDialog = false
        if (hostConnectionLostAtMillis == null) {
            connectionLostSnoozedUntilMillis = 0L
            return@LaunchedEffect
        }
        if (isHost || !isTopDestination || !activityResumed || !connectionRecoveryPromptEnabled || connectionPromptSilenced) return@LaunchedEffect
        val hostLostAtMillis = hostConnectionLostAtMillis
        val dueAtMillis = ConnectionRecoveryPromptPolicy.dueAtMillis(
            hostConnectionLostAtMillis = hostLostAtMillis,
            configuredDelayMillis = connectionRecoveryPromptDelayMillis,
            snoozedUntilMillis = connectionLostSnoozedUntilMillis,
        ) ?: return@LaunchedEffect
        delay((dueAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
        if (ConnectionRecoveryPromptPolicy.shouldPrompt(
                hostConnectionLostAtMillis = hostLostAtMillis,
                configuredDelayMillis = connectionRecoveryPromptDelayMillis,
                snoozedUntilMillis = connectionLostSnoozedUntilMillis,
                nowMillis = System.currentTimeMillis(),
                isGuest = !isHost,
                isRadarTopDestination = isTopDestination,
                activityResumed = activityResumed,
                settingEnabled = connectionRecoveryPromptEnabled,
                silencedForRing = connectionPromptSilenced,
            )
        ) {
            showConnectionLostDialog = true
        }
    }

    fun snoozeConnectionLostPrompt() {
        showConnectionLostDialog = false
        connectionLostSnoozedUntilMillis = System.currentTimeMillis() + ConnectionRecoveryPromptPolicy.SNOOZE_MILLIS
    }

    val peers by discoveryRepository?.members?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<RadarPeerUpdate>()) }
    val connectivity by discoveryRepository?.connectivityStatus?.collectAsState()
        ?: remember { mutableStateOf(ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true)) }
    val activeBeacons by discoveryRepository?.activeBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyMap()) }
    val repositoryHolders by discoveryRepository?.chatColourHolders?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<Long, String>()) }
    val chatColourHolders = chatColourHoldersOverride ?: repositoryHolders
    val endedBeacons by discoveryRepository?.endedBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val requestsToShow = pendingJoinRequests
    val localBeacon by discoveryRepository?.localBeacon?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val silencedBeaconIds by discoveryRepository?.silencedBeaconIds?.collectAsState()
        ?: remember { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current
    val accessibilityEnvironment = rememberAccessibilityEnvironment(reduceMotionEnabled)
    val localPeerId = localPeerIdOverride ?: discoveryRepository?.localPeerId
    val focusSelection by discoveryRepository?.focusedPeerId?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val focusedPeer = remember(peers, focusSelection, sessionState, isHost) {
        val resolvedId = FocusPolicy.resolveFocus(
            selectedPeerId = focusSelection,
            availablePeerIds = peers.map { it.peerId },
            peerJoinedAt = peers.associate { it.peerId to it.joinedAtMillis },
            isHost = isHost,
            localPeerId = localPeerId ?: "",
            hostPeerId = sessionState.currentEvent?.hostPeerId
        )
        peers.firstOrNull { it.peerId == resolvedId }
    }
    val lastKnownDirection = lastKnownPeerDirections.firstOrNull { it.peerId == focusedPeer?.peerId }
        ?: lastKnownPeerDirections.firstOrNull()
    var lastKnownClockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val trackedDirectionIsLastKnown = focusedPeer?.radarDirectionIsLastKnown == true
    LaunchedEffect(lastKnownDirection?.peerId, trackedDirectionIsLastKnown) {
        if (lastKnownDirection != null || trackedDirectionIsLastKnown) {
            while (true) {
                delay(if (trackedDirectionIsLastKnown) 500L else 5_000L)
                lastKnownClockMillis = System.currentTimeMillis()
            }
        }
    }
    val trackedDirectionAgeMillis = focusedPeer?.radarDirectionUpdatedAtMillis?.let {
        (lastKnownClockMillis - it).coerceAtLeast(0L)
    }
    val trackedDirectionAlpha = when {
        focusedPeer?.radarAzimuth == null -> 0f
        trackedDirectionIsLastKnown && trackedDirectionAgeMillis != null ->
            RadarDirectionPolicy.lastKnownDirectionAlpha(trackedDirectionAgeMillis)
        trackedDirectionIsLastKnown -> 0f
        else -> 1f
    }
    val showLastKnownDirection = trackedDirectionIsLastKnown && trackedDirectionAlpha > 0f
    val senderName = sessionState.userName.ifBlank {
        context.getString(if (isHost) R.string.host_default_name else R.string.peer_default_name)
    }
    val focusedBeacon = focusedPeer?.let { activeBeacons[it.peerId] } ?: localBeacon
    val announcementPolicy = remember(eventId) { RadarAnnouncementPolicy() }
    var spokenUpdateText by remember(eventId) { mutableStateOf<String?>(null) }
    val announcementState = RadarAnnouncementPolicy.State(
        focusedPeer?.peerId,
        RadarFeedbackPolicy.proximityState(focusedPeer?.distance, focusedPeer?.distanceSource, focusedPeer?.proximity),
        focusedBeacon?.title,
    )
    val focusedPeerName = focusedPeer?.let {
        if (it.syntheticRanging) context.getString(R.string.simulated_peer_label, it.name) else it.name
    }
    val spokenSummary by rememberUpdatedState(
        RadarAccessibilityProbe.describeLocalized(
            resources = context.resources,
            peerName = focusedPeerName,
            distanceMeters = focusedPeer?.distance,
            azimuthDegrees = focusedPeer?.radarAzimuth,
            directionUncertaintyDegrees = focusedPeer?.displayedEstimatedDirection?.uncertaintyDegrees,
            proximityLabel = focusedPeer?.proximity?.let { proximity ->
                context.getString(when (proximity) {
                    "Hot" -> R.string.proximity_hot
                    "Cold" -> R.string.proximity_cold
                    else -> R.string.proximity_unknown
                })
            },
            beaconTitle = focusedBeacon?.title,
            distanceIsEstimate = focusedPeer?.distanceSource == RangingSourcePolicy.RangingSource.GNSS,
            distanceAccuracyMeters = focusedPeer?.distanceAccuracyMeters,
        ) + (if (focusedPeer == null && focusedBeacon != null) {
            context.getString(R.string.radar_active_beacon, focusedBeacon.title)
        } else "") + (if (showLastKnownDirection) {
            " ${context.getString(R.string.radar_last_known_indicator)}."
        } else "")
    )
    val announcementsActive = spokenRadarUpdatesEnabled && accessibilityEnvironment.screenReaderActive &&
            activityResumed && isTopDestination && !showEndConfirmation && !showBeaconSheet &&
            !showQrCode && !showConnectionLostDialog
    LaunchedEffect(announcementsActive, announcementState) {
        if (!announcementsActive) {
            spokenUpdateText = null
            return@LaunchedEffect
        }
        delay(1_500L)
        val waitMillis = announcementPolicy.delayBeforeAnnouncement(announcementState, android.os.SystemClock.elapsedRealtime())
            ?: return@LaunchedEffect
        delay(waitMillis)
        spokenUpdateText = spokenSummary
        announcementPolicy.markAnnounced(announcementState, android.os.SystemClock.elapsedRealtime())
    }

    Scaffold(
        topBar = {
            RadarTopAppBar(
                eventId = eventId,
                peerCount = peers.size + 1,
                maxPeers = MAX_PARTICIPANTS,
                isHost = isHost,
                acceptsNewMembers = sessionState.currentEvent?.acceptsNewMembers ?: true,
                onShowEndConfirmation = { showEndConfirmation = true },
                onShowMemberSheet = { showMemberSheet = true },
                onShowQrCode = { showQrCode = true },
                onEventSettings = onEventSettings,
                hideMemberCount = showMemberSheet
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        JoinRequestOverlay(
            requests = if (isHost) requestsToShow else emptyList(),
            onApprove = onApproveJoinRequest,
            onDeny = onDenyJoinRequest,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            coordinationError?.let { message -> RadarCoordinationErrorCard(message) }
            RadarNoticeCards(
                peers = peers,
                focusedPeer = focusedPeer,
                lastKnownDirection = lastKnownDirection,
                lastKnownClockMillis = lastKnownClockMillis,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                RadarAdaptiveContent(
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    sessionState = sessionState,
                    peers = peers,
                    focusedPeer = focusedPeer,
                    focusedBeacon = focusedBeacon,
                    activeBeacons = activeBeacons,
                    endedBeacons = endedBeacons,
                    localBeacon = localBeacon,
                    connectivity = connectivity,
                    discoveryRepository = discoveryRepository,
                    localPeerId = localPeerId,
                    senderName = senderName,
                    chatBubbleColorArgb = chatBubbleColorArgb,
                    readReceiptsVisible = activityResumed && isTopDestination && readReceiptsEnabled,
                    silencedBeaconIds = silencedBeaconIds,
                    isHost = isHost,
                    radarScreenReaderSummaryEnabled = radarScreenReaderSummaryEnabled,
                    reduceMotionEnabled = accessibilityEnvironment.reduceMotion,
                    spokenUpdateText = spokenUpdateText,
                    trackedDirectionAlpha = trackedDirectionAlpha,
                    showLastKnownDirection = showLastKnownDirection,
                    onShowChatSettings = { showChatSettings = true },
                    onShowBeaconSheet = { showBeaconSheet = true },
                )
            }
            RadarScreenOverlays(
                eventId = eventId,
                sessionState = sessionState,
                isHost = isHost,
                discoveryRepository = discoveryRepository,
                peers = peers,
                connectivity = connectivity,
                activeBeacons = activeBeacons,
                localBeacon = localBeacon,
                focusedPeerId = focusedPeer?.peerId,
                senderName = senderName,
                showEndConfirmation = showEndConfirmation,
                onDismissEndConfirmation = { showEndConfirmation = false },
                onLeaveEvent = onLeaveEvent,
                onEndEvent = onEndEvent,
                showConnectionLostDialog = showConnectionLostDialog,
                onDismissConnectionLost = ::snoozeConnectionLostPrompt,
                onLeaveFromConnectionLost = { showConnectionLostDialog = false; onLeaveEvent() },
                onSilenceConnectionLost = { connectionPromptSilenced = true; showConnectionLostDialog = false },
                connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis,
                showChatSettings = showChatSettings,
                onDismissChatSettings = { showChatSettings = false },
                chatBubbleColorArgb = chatBubbleColorArgb,
                onUsernameChange = onUsernameChange,
                onChatBubbleColorChange = onChatBubbleColorChange,
                onColourClaimResult = { requested, granted ->
                    refused = if (granted) null else requested
                },
                colourHolders = chatColourHolders,
                localPeerId = localPeerId,
                refusedChatColourArgb = refused,
                refusedChatColourFallback = refusedChatColourArgb,
                readReceiptsEnabled = readReceiptsEnabled,
                onReadReceiptsChanged = onReadReceiptsChanged,
                showBeaconSheet = showBeaconSheet,
                onDismissBeaconSheet = { showBeaconSheet = false },
                showMemberSheet = showMemberSheet,
                onDismissMemberSheet = { showMemberSheet = false },
                showQrCode = showQrCode,
                onDismissQrCode = { showQrCode = false },
            )
        }
    }
}
