package com.fkurevija.pingring.ui.screens

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
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.ChatBubbleColorPolicy
import com.fkurevija.pingring.data.ChatTimelineItem
import com.fkurevija.pingring.data.ChatTimelinePolicy
import com.fkurevija.pingring.data.ConnectionRecoveryPromptPolicy
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.FocusPolicy
import com.fkurevija.pingring.data.LastKnownPeerDirection
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.RadarAccessibilityProbe
import com.fkurevija.pingring.data.RadarAnnouncementPolicy
import com.fkurevija.pingring.data.RadarDirectionPolicy
import com.fkurevija.pingring.data.RadarFeedbackPolicy
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.data.RangingSourcePolicy
import com.fkurevija.pingring.data.SessionState
import com.fkurevija.pingring.ui.accessibility.rememberAccessibilityEnvironment
import kotlinx.coroutines.delay
import kotlin.math.atan2

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
    val spokenSummary by rememberUpdatedState(
        RadarAccessibilityProbe.describeLocalized(
            resources = context.resources,
            peerName = focusedPeer?.name,
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
                onEventSettings = onEventSettings
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
            coordinationError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
            val weakSignalPeers = peers.filter { it.signalWeak }
            val trackedWeakPeer = focusedPeer?.takeIf { it.signalWeak }
            val weakSignalMessage = when {
                trackedWeakPeer != null -> stringResource(R.string.radar_weak_tracked_signal, trackedWeakPeer.name)
                weakSignalPeers.isNotEmpty() -> stringResource(R.string.radar_weak_group_signal, weakSignalPeers.size)
                else -> null
            }
            weakSignalMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(text = message, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                    }
                }
            }
            lastKnownDirection?.let { location ->
                val bearingDegrees = (Math.toDegrees(atan2(location.worldDirectionX.toDouble(), location.worldDirectionY.toDouble())).toInt() % 360 + 360) % 360
                val secondsAgo = ((lastKnownClockMillis - location.lastDetectedAtMillis).coerceAtLeast(0L) / 1_000L).toInt()
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.radar_last_known_direction, location.name, bearingDegrees, secondsAgo),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    val paneWidth = (maxWidth - 20.dp) / 2
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RadarContent(
                            focusedPeer,
                            peers.isNotEmpty(),
                            focusedBeacon,
                            radarScreenReaderSummaryEnabled,
                            accessibilityEnvironment.reduceMotion,
                            spokenUpdateText,
                            Modifier.width(paneWidth),
                            trackedDirectionAlpha,
                            showLastKnownDirection,
                        )
                        PeerList(
                            peers,
                            connectivity,
                            discoveryRepository,
                            activeBeacons,
                            focusedPeerId = focusedPeer?.peerId,
                            onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                            isHost = isHost,
                            onRemovePeer = { discoveryRepository?.removeMember(it) },
                            modifier = Modifier.width(paneWidth)
                        )
                    }
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    val isImeVisible = WindowInsets.isImeVisible
                    val chatEntries = remember(sessionState.chatMessages, activeBeacons, endedBeacons) {
                        ChatTimelinePolicy.build(
                            messages = sessionState.chatMessages,
                            activeBeacons = activeBeacons.values.toList(),
                            endedBeacons = endedBeacons
                        ).reversed()
                    }
                    val chatListState = rememberLazyListState()
                    val latestSenderColors = remember(sessionState.chatMessages) { ChatBubbleColorPolicy.latestColorsBySender(sessionState.chatMessages) }
                    var followingNewest by remember { mutableStateOf(true) }
                    LaunchedEffect(chatListState) {
                        var dragging = false
                        while (true) {
                            withFrameNanos { }
                            if (chatListState.isScrollInProgress) dragging = true else if (dragging) {
                                followingNewest = chatListState.firstVisibleItemIndex == 0
                                dragging = false
                            }
                        }
                    }
                    LaunchedEffect(chatEntries.firstOrNull()?.key) { if (followingNewest) chatListState.scrollToItem(0) }
                    val density = LocalDensity.current
                    var topContentHeightPx by remember { mutableIntStateOf(0) }
                    val availableHeight = maxHeight
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().onSizeChanged { topContentHeightPx = it.height }) {
                            RadarContent(
                                focusedPeer,
                                peers.isNotEmpty(),
                                focusedBeacon,
                                radarScreenReaderSummaryEnabled,
                                accessibilityEnvironment.reduceMotion,
                                spokenUpdateText,
                                Modifier.fillMaxWidth(),
                                trackedDirectionAlpha,
                                showLastKnownDirection,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = { showChatSettings = true }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.chat_settings), textAlign = TextAlign.Center)
                                }
                                BeaconActionButton(
                                    localBeacon = localBeacon,
                                    onOpen = { showBeaconSheet = true },
                                    onStop = { discoveryRepository?.cancelLocalBeacon() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        val topContentHeightDp = with(density) { topContentHeightPx.toDp() }
                        val chatCardOffsetY = if (isImeVisible) 0.dp else topContentHeightDp
                        val chatCardHeight = if (isImeVisible) availableHeight else (availableHeight - topContentHeightDp).coerceAtLeast(0.dp)
                        RadarChatCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = chatCardOffsetY)
                                .height(chatCardHeight)
                                .padding(vertical = 6.dp)
                                .then(if (isImeVisible) Modifier.imePadding() else Modifier),
                            isImeVisible = isImeVisible,
                            chatListState = chatListState,
                            chatEntries = chatEntries,
                            localPeerId = localPeerId,
                            chatBubbleColorArgb = chatBubbleColorArgb,
                            latestSenderColors = latestSenderColors,
                            silencedBeaconIds = silencedBeaconIds,
                            onToggleBeaconSilenced = { id, silenced -> discoveryRepository?.setBeaconSilenced(id, silenced) },
                            onSendMessage = { msg -> discoveryRepository?.broadcastOutgoingChatMessage(msg, senderName, chatBubbleColorArgb) }
                        )
                    }
                }
            }
            if (showEndConfirmation) {
                EndPingRingDialog(isHost = isHost, onConfirm = { showEndConfirmation = false; if (isHost) onEndEvent() else onLeaveEvent() }, onDismiss = { showEndConfirmation = false })
            }
            if (showConnectionLostDialog) {
                ConnectionLostDialog(
                    onDismiss = ::snoozeConnectionLostPrompt,
                    onLeave = { showConnectionLostDialog = false; onLeaveEvent() },
                    onSilence = { connectionPromptSilenced = true; showConnectionLostDialog = false },
                    connectionRecoveryPromptDelayMillis = connectionRecoveryPromptDelayMillis
                )
            }
            if (showChatSettings) {
                ChatSettingsDialog(
                    selectedColorArgb = chatBubbleColorArgb,
                    username = sessionState.userName,
                    existingMemberNames = sessionState.friends.map { it.name },
                    onUsernameChange = onUsernameChange,
                    onColorSelected = { requested ->
                        val repo = discoveryRepository
                        if (repo == null) onChatBubbleColorChange(requested) else repo.requestChatColour(requested) { granted ->
                            refused = if (granted) null else requested
                            if (granted) onChatBubbleColorChange(requested)
                        }
                    },
                    onDismiss = { showChatSettings = false },
                    colourHolders = chatColourHolders,
                    memberNameFor = { peerId -> peers.firstOrNull { it.peerId == peerId }?.name ?: peerId },
                    localPeerId = localPeerId,
                    refusedColorArgb = refused ?: refusedChatColourArgb,
                )
            }
            if (showBeaconSheet) {
                BeaconCustomizationDialog(
                    localBeacon = localBeacon,
                    onDismiss = { showBeaconSheet = false },
                    onActivate = { title, color, pattern -> discoveryRepository?.activateBeacon(title, color, pattern, senderName) },
                    onStop = { discoveryRepository?.cancelLocalBeacon() },
                    onTestVibrate = { pattern -> discoveryRepository?.playVibrationPattern(pattern) }
                )
            }
            if (showMemberSheet) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = { showMemberSheet = false }, sheetState = sheetState) {
                    PeerList(
                        peers = peers,
                        connectivity = connectivity,
                        repository = discoveryRepository,
                        activeBeacons = activeBeacons,
                        focusedPeerId = focusedPeer?.peerId,
                        onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                        isHost = isHost,
                        onRemovePeer = { discoveryRepository?.removeMember(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
            }
            if (showQrCode) {
                AlertDialog(
                    onDismissRequest = { showQrCode = false },
                    title = { Text(stringResource(R.string.scan_to_join)) },
                    text = {
                        PingRingCodeBlock(eventCode = eventId, inviteSecret = sessionState.currentEvent?.inviteSecret, qrSize = 200.dp)
                    },
                    confirmButton = { TextButton(onClick = { showQrCode = false }) { Text(stringResource(R.string.close)) } }
                )
            }
        }
    }
}
