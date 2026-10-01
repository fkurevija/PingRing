package com.fkurevija.pingring.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.layout.onSizeChanged
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.BeaconPreset
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.ChatMessage
import com.fkurevija.pingring.data.ChatMessageOwnership
import com.fkurevija.pingring.data.ChatTimelineItem
import com.fkurevija.pingring.data.ChatTimelinePolicy
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DEFAULT_BEACON_TITLE
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.FocusPolicy
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.QuickBeacon
import com.fkurevija.pingring.data.RadarAccessibilityPolicy
import com.fkurevija.pingring.data.RadarDirectionPolicy
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.data.SessionState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colour this beacon is sent in.
 *
 * Every surface that shows a beacon - the banner, the button that sets it, the
 * ring, the chat entry, the peer's row - takes its colour from here, so they
 * cannot drift apart and leave one of them showing a colour the sender never
 * chose.
 */
private val BeaconInfo.color: Color get() = Color(colorArgb)

/**
 * The text colour that reads on this one: black or white, whichever has more
 * contrast against it.
 *
 * Material's own contentColorFor switches at a luminance of 0.5, which is
 * tuned for tonal surfaces and leaves white on a bright beacon green at 1.7:1 -
 * the least readable pairing on a button that is meant to be read at a glance.
 * Comparing the two contrast ratios instead follows the colour wherever the
 * user has put it.
 */
private fun Color.readableOnIt(): Color {
    val contrastOnBlack = (luminance() + 0.05f) / 0.05f
    val contrastOnWhite = 1.05f / (luminance() + 0.05f)
    return if (contrastOnBlack >= contrastOnWhite) Color.Black else Color.White
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    eventId: String,
    isHost: Boolean,
    trackHost: Boolean,
    coordinationError: String?,
    discoveryRepository: DiscoveryRepository?,
    sessionState: SessionState,
    quickBeacon: QuickBeacon?,
    chatBubbleColorArgb: Long?,
    radarScreenReaderSummaryEnabled: Boolean,
    onQuickBeaconChange: (QuickBeacon?) -> Unit,
    /**
     * Devices waiting on the host to approve, and the host's answers to them.
     *
     * Parameters rather than read from the repository, like every other piece of
     * state on this screen: the radar has to compose with no radio at all, which
     * is the only way any of it is testable. Defaults keep the existing call
     * sites and the tests below honest about a ring with nothing waiting.
     */
    pendingJoinRequests: List<PendingJoinRequest> = emptyList(),
    onApproveJoinRequest: (String) -> Unit = {},
    onDenyJoinRequest: (String) -> Unit = {},
    onEventSettings: () -> Unit,
    onLeaveEvent: () -> Unit,
    onEndEvent: () -> Unit,
) {
    var showEndConfirmation by remember { mutableStateOf(false) }
    var showBeaconSheet by remember { mutableStateOf(false) }
    var showQrCode by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    // The radar is the screen a live PingRing runs on, so the system back
    // (button or gesture) must not quietly drop the user out of the app: that
    // pops the only entry off the back stack and leaves the activity, which
    // looks like the app closing while members are still being tracked. Ask
    // first, and let the user decide.
    //
    // Only the back event is intercepted. The home and recents buttons are not
    // routed through the back dispatcher, so they keep backgrounding the app
    // with the PingRing still running on in the foreground service - which is
    // the point of [com.fkurevija.pingring.data.PingRingService] - and the
    // persistent notification brings the user back to this screen.
    //
    // While this dialog is up it is a real dialog window, so a further back
    // press dismisses it instead of re-opening it, and an open keyboard still
    // consumes back first to dismiss itself.
    BackHandler {
        showEndConfirmation = true
    }

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    LaunchedEffect(discoveryRepository) {
        discoveryRepository?.startDiscovery()
    }
    // Deliberately no onDispose-triggered stopDiscovery() here: HostSetupScreen
    // and RadarScreen share the same DiscoveryRepository instance for the
    // lifetime of an event (e.g. navigating to the event settings screen and
    // back must not drop the connection). Discovery is only stopped
    // explicitly when the user actually leaves/ends the event, via
    // onLeaveEvent/onEndEvent in MainActivity.

    val peers by discoveryRepository?.peerUpdates?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<RadarPeerUpdate>()) }
    val connectivity by discoveryRepository?.connectivityStatus?.collectAsState()
        ?: remember { mutableStateOf(ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true)) }
    val activeBeacons by discoveryRepository?.activeBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyMap()) }
    // Beacons that have ended, so a finished one keeps its place in the
    // transcript instead of disappearing. See ChatTimelinePolicy.
    val endedBeacons by discoveryRepository?.endedBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    // Devices waiting on the host to say yes. Only ever non-empty on a host, and
    // only while one is being held open - see NearbyTransport.pendingJoinRequests.
    // Taken as a parameter rather than read from the repository, so the radar can
    // be composed - and tested - with no radio at all.
    val requestsToShow = pendingJoinRequests
    val localBeacon by discoveryRepository?.localBeacon?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val haptic = LocalHapticFeedback.current
    // For the one toast on this screen, which has to fire from inside a click
    // handler rather than from a LaunchedEffect, so it needs a context captured
    // here rather than one read at the call site.
    val context = LocalContext.current

    // The focused member, chosen by the user or defaulted by FocusPolicy. The
    // radar draws only this person, which is what makes a full 8-member
    // PingRing readable, and the UWB session follows the same choice.
    val focusSelection by discoveryRepository?.focusedPeerId?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val focusedPeer = remember(peers, focusSelection, sessionState, isHost, trackHost) {
        val resolvedId = FocusPolicy.resolveFocus(
            selectedPeerId = focusSelection,
            availablePeerIds = peers.map { it.peerId },
            peerJoinedAt = peers.associate { it.peerId to it.joinedAtMillis },
            isHost = isHost,
            trackHost = trackHost,
            localPeerId = discoveryRepository?.localPeerId ?: "",
            hostPeerId = sessionState.currentEvent?.hostPeerId
        )
        peers.firstOrNull { it.peerId == resolvedId }
    }

    // A guest under "track host" has no say in this: the host set the policy
    // for the whole event, so the picker is shown but disabled rather than
    // hidden, which would leave the radar pointed at somebody with no visible
    // way to find out why.
    val canSelectTrackedMember = FocusPolicy.canUserSelect(isHost, trackHost)

    // Proximity feedback follows the tracked member, not whoever happens to be
    // closest, so it is a haptic about the person the user is actually watching.
    LaunchedEffect(focusedPeer?.peerId, focusedPeer?.distance) {
        val distance = focusedPeer?.distance ?: return@LaunchedEffect
        if (distance < 2f) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val senderName = sessionState.userName.ifBlank { if (isHost) "Host" else "Peer" }
    val focusedBeacon = focusedPeer?.let { activeBeacons[it.peerId] } ?: localBeacon

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PingRing", fontWeight = FontWeight.Bold)
                        Text(
                            "Code: $eventId · ${peers.size + 1}/$MAX_PARTICIPANTS Members",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showEndConfirmation = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isHost) "End PingRing" else "Leave PingRing"
                        )
                    }
                },
                actions = {
                    val acceptsNewMembers = sessionState.currentEvent?.acceptsNewMembers ?: true
                    if (acceptsNewMembers) {
                        IconButton(onClick = { showQrCode = true }) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = "Show QR code to join this PingRing"
                            )
                        }
                    }
                    if (isHost) {
                        TextButton(onClick = onEventSettings) { Text("Settings") }
                    }
                }
            )
        },
        // No bottomBar: End/Leave moved to the PingRing Settings screen, where
        // it sits with the other destructive actions instead of occupying a
        // permanent bar above the chat. The navigation bar's back arrow still
        // opens the same confirmation, so there is a way out from the radar
        // itself and the two can never disagree about what leaving means.
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            coordinationError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Devices waiting to be let in. Above the beacon banner: a request is
            // a question being asked of the host right now, and a live beacon is
            // a statement already made. Approving is the accept that opens the
            // guest's payload channel, so it happens here and nowhere else.
            if (isHost && requestsToShow.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (requestsToShow.size == 1) {
                                "1 device wants to join"
                            } else {
                                "${requestsToShow.size} devices want to join"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        requestsToShow.forEach { request ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    request.deviceName,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { onDenyJoinRequest(request.endpointId) }) {
                                    Text("Deny")
                                }
                                Spacer(Modifier.width(4.dp))
                                Button(onClick = { onApproveJoinRequest(request.endpointId) }) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }

            // Local Active Beacon Banner
            localBeacon?.let { beacon ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = beacon.color.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.Campaign,
                                contentDescription = null,
                                tint = beacon.color,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Your Beacon is Active: ${beacon.title}",
                                    fontWeight = FontWeight.Bold,
                                    color = beacon.color
                                )
                                Text(
                                    "Calling nearby peers to your location",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        // Outlined, and in the beacon's own colour rather than the
                        // theme's error colour: the banner is the beacon, and a
                        // red button sitting in the middle of a green beacon
                        // reads as a different kind of warning entirely. The
                        // border is what keeps it unmistakably a button.
                        OutlinedButton(
                            onClick = { discoveryRepository?.cancelLocalBeacon() },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, beacon.color),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = beacon.color)
                        ) {
                            Text("Stop")
                        }
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
                            Modifier.width(paneWidth)
                        )
                        PeerList(
                            peers,
                            connectivity,
                            discoveryRepository,
                            activeBeacons,
                            focusedPeerId = focusSelection,
                            canSelect = canSelectTrackedMember,
                            onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                            modifier = Modifier.width(paneWidth)
                        )
                    }
                } else {
                    var chatText by remember { mutableStateOf("") }
                    @OptIn(ExperimentalLayoutApi::class)
                    val isImeVisible = WindowInsets.isImeVisible

                    // The transcript is built here rather than inside the list,
                    // so the follow-the-newest rule below can watch it and the
                    // list is not rebuilt on every scroll.
                    //
                    // Messages and ended beacons are one conversation ordered by
                    // when each was said, so a beacon that has stopped settles
                    // back among the messages around it. Beacons still running
                    // are current state and stay pinned to the near end, where
                    // the eye already is. See ChatTimelinePolicy.
                    //
                    // Every active beacon is listed, the local one included. A
                    // beacon is broadcast to the ring, so the transcript has to
                    // read the same on every device, and the sender is as much a
                    // member of the ring as anyone else: a beacon you set appears
                    // in your own chat too. The sender keeps its banner above the
                    // radar as well, which is where the Stop control lives.
                    //
                    // Newest first, because the list is reversed: what reads as the
                    // end of the conversation is the bottom of the screen, which
                    // is where the reader is already looking.
                    val chatEntries: List<ChatTimelineItem> = remember(
                        sessionState.chatMessages,
                        activeBeacons,
                        endedBeacons,
                    ) {
                        ChatTimelinePolicy.build(
                            messages = sessionState.chatMessages,
                            activeBeacons = activeBeacons.values.toList(),
                            endedBeacons = endedBeacons,
                        ).reversed()
                    }

                    val chatListState = rememberLazyListState()

                    /**
                     * Whether the transcript is parked at the newest end, which is
                     * where it starts and returns to.
                     *
                     * Only a scroll the reader started can turn this off, which is
                     * the whole point: a list that is merely re-laid out - because
                     * a message arrived, or the keyboard opened - must not look
                     * like the reader choosing to look away. When they are reading
                     * back through the history a new message waits at the bottom
                     * for them instead of yanking the page out from under them,
                     * and the drag up that brings them back turns this on again.
                     */
                    var followingNewest by remember { mutableStateOf(true) }
                    LaunchedEffect(chatListState) {
                        var readerIsDragging = false
                        while (true) {
                            withFrameNanos { }
                            if (chatListState.isScrollInProgress) {
                                readerIsDragging = true
                            } else if (readerIsDragging) {
                                // Decided once the drag has come to rest, so a
                                // fling counts as far away as it actually ended.
                                followingNewest = chatListState.firstVisibleItemIndex == 0
                                readerIsDragging = false
                            }
                        }
                    }
                    LaunchedEffect(chatEntries.firstOrNull()?.key) {
                        if (followingNewest) chatListState.scrollToItem(0)
                    }

                    val renderChatCard: @Composable (Modifier) -> Unit = { cardModifier ->
                        Card(
                            modifier = cardModifier,
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isImeVisible) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (isImeVisible) 8.dp else 0.dp)
                        ) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text(
                                    "Chat",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(6.dp))
                                LazyColumn(
                                    state = chatListState,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    // Laid out from the newest entry backwards, so the
                                    // newest message rests just above the text box and
                                    // everything older stacks up behind it, the way a
                                    // conversation reads. The list does still have to be
                                    // told to follow the newest entry: a lazy list
                                    // deliberately holds on to whatever it was showing
                                    // when items are added to it, which is right for a
                                    // page being read and wrong for a chat, where a
                                    // message arriving has to push the transcript up.
                                    reverseLayout = true
                                ) {

                                    if (chatEntries.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier.fillParentMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "No messages yet. Say hello!",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    } else {
                                        items(chatEntries, key = { it.key }) { entry ->
                                            when (entry) {
                                                is ChatTimelineItem.Msg -> {
                                                    val msg = entry.message
                                                    // Identity, not display name: a
                                                    // name comparison put the host's own
                                                    // messages on the wrong side, because
                                                    // the local fallback name is "Host"
                                                    // and names are neither unique nor
                                                    // stable. See ChatMessageOwnership.
                                                    val isMe = ChatMessageOwnership.isOwnMessage(
                                                msg.senderPeerId,
                                                discoveryRepository?.localPeerId
                                            )
                                            val senderBubbleColor = msg.colorArgb?.let(::Color)
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                            ) {
                                                Card(
                                                    shape = RoundedCornerShape(
                                                        topStart = 14.dp,
                                                        topEnd = 14.dp,
                                                        bottomStart = if (isMe) 14.dp else 4.dp,
                                                        bottomEnd = if (isMe) 4.dp else 14.dp
                                                    ),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = senderBubbleColor ?: if (isMe) {
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.surface
                                                        }
                                                    )
                                                ) {
                                                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                                                Text(
                                                                    msg.senderName,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = senderBubbleColor?.readableOnIt() ?: if (isMe) {
                                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                                    } else {
                                                                        MaterialTheme.colorScheme.primary
                                                                    }
                                                                )
                                                                Text(
                                                                    msg.messageText,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    color = senderBubbleColor?.readableOnIt()
                                                                        ?: if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                is ChatTimelineItem.Beacon -> ActiveBeaconEntry(entry.beacon)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = chatText,
                                        onValueChange = { chatText = it },
                                        placeholder = { Text("Type a message…", style = MaterialTheme.typography.bodyMedium) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(24.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (chatText.isNotBlank()) {
                                                discoveryRepository?.broadcastOutgoingChatMessage(
                                                    chatText.trim(),
                                                    senderName,
                                                    chatBubbleColorArgb,
                                                )
                                                chatText = ""
                                                focusManager.clearFocus()
                                            }
                                        },
                                        shape = CircleShape,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                        modifier = Modifier.size(46.dp),
                                        enabled = chatText.isNotBlank()
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val density = LocalDensity.current
                    var topContentHeightPx by remember { mutableIntStateOf(0) }
                    val availableHeight = maxHeight

                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { topContentHeightPx = it.height }
                        ) {
                            RadarContent(
                                focusedPeer,
                                peers.isNotEmpty(),
                                focusedBeacon,
                                radarScreenReaderSummaryEnabled,
                                // Height is left to the content: a fixed height
                                // clipped the ring, since the ring plus the name
                                // above it and the readout below need more room
                                // than any single fixed value can promise.
                                Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // One tap on the configuration set as the Quick
                                // Beacon in the beacon screen. With none set yet
                                // there is nothing to send, so the tap says so
                                // and opens that screen - the same place the
                                // Quick Beacon is chosen. Opening it alone would
                                // read as a misfired button, since the tap
                                // visibly does something that has nothing to do
                                // with beacons.
                                OutlinedButton(
                                    onClick = {
                                        if (quickBeacon != null) {
                                            discoveryRepository?.activateBeacon(
                                                quickBeacon.title,
                                                quickBeacon.colorArgb,
                                                quickBeacon.pattern.name,
                                                senderName
                                            )
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "No Quick Beacon set yet. Open New Beacon and " +
                                                    "tap Set as Quick Beacon to choose one.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            showBeaconSheet = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Quick Beacon")
                                }
                                // The label and the icon are coloured for the
                                // beacon they sit on rather than left at the
                                // theme's on-primary, which is dark grey and only
                                // happens to work on some beacon colours. The
                                // colour is the user's own pick, so only its
                                // contrast can say what reads on top of it.
                                //
                                // Read once, so the colour the button is painted
                                // and the one its label is drawn for cannot come
                                // from two different beacons.
                                Button(
                                    onClick = { showBeaconSheet = true },
                                    modifier = Modifier.weight(1f),
                                    colors = localBeacon?.let { beacon ->
                                        ButtonDefaults.buttonColors(
                                            containerColor = beacon.color,
                                            contentColor = beacon.color.readableOnIt()
                                        )
                                    } ?: ButtonDefaults.buttonColors()
                                ) {
                                    Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (localBeacon != null) "My Beacon" else "New Beacon")
                                }
                            }
                        }

                        val topContentHeightDp = with(density) { topContentHeightPx.toDp() }
                        val chatCardOffsetY = if (isImeVisible) 0.dp else topContentHeightDp
                        val chatCardHeight = if (isImeVisible) {
                            availableHeight
                        } else {
                            (availableHeight - topContentHeightDp).coerceAtLeast(0.dp)
                        }

                        // Single stable call site keeps the chat text field's composition
                        // identity intact across docked/floating states, preserving focus
                        // and the IME connection when the keyboard opens.
                        renderChatCard(
                            Modifier
                                .fillMaxWidth()
                                .offset(y = chatCardOffsetY)
                                .height(chatCardHeight)
                                .padding(vertical = 6.dp)
                                .then(if (isImeVisible) Modifier.imePadding() else Modifier)
                        )
                    }
                }

                if (showEndConfirmation) {
                    EndPingRingDialog(
                        isHost = isHost,
                        onConfirm = {
                            showEndConfirmation = false
                            if (isHost) onEndEvent() else onLeaveEvent()
                        },
                        onDismiss = { showEndConfirmation = false }
                    )
                }

                if (showBeaconSheet) {
                    BeaconCustomizationDialog(
                        localBeacon = localBeacon,
                        quickBeacon = quickBeacon,
                        onDismiss = { showBeaconSheet = false },
                        onActivate = { title, colorArgb, patternName ->
                            discoveryRepository?.activateBeacon(title, colorArgb, patternName, senderName)
                        },
                        onQuickBeaconChange = { onQuickBeaconChange(it) },
                        onStop = {
                            discoveryRepository?.cancelLocalBeacon()
                        },
                        onTestVibrate = { pattern ->
                            discoveryRepository?.playVibrationPattern(pattern)
                        }
                    )
                }

                if (showQrCode) {
                    AlertDialog(
                        onDismissRequest = { showQrCode = false },
                        title = { Text("Scan to Join") },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = generateQrCode(eventId).asImageBitmap(),
                                        contentDescription = "QR code for PingRing $eventId",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    eventId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showQrCode = false }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }
}

/**
 * A live beacon, rendered inside the chat transcript.
 *
 * Built to be unmistakably not a chat bubble: it always spans the full width
 * rather than sitting left or right, it carries a full-strength band of the
 * beacon's own colour rather than a 15% tint, and it has a square silhouette
 * where messages have a 14 dp bubble. Someone glancing at the chat should see
 * a beacon before they read a single word of it.
 */
@Composable
private fun ActiveBeaconEntry(beacon: BeaconInfo) {
    val beaconColor = beacon.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                beaconColor.copy(alpha = 0.18f),
                RoundedCornerShape(8.dp)
            )
            .border(1.dp, beaconColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Campaign,
            contentDescription = "Active beacon",
            tint = beaconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                beacon.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = beaconColor
            )
            Text(
                "${beacon.senderName} · ${BeaconVibrationPattern.fromName(beacon.vibrationPattern).displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RadarContent(
    peer: RadarPeerUpdate?,
    hasPeers: Boolean,
    beacon: BeaconInfo?,
    radarScreenReaderSummaryEnabled: Boolean,
    modifier: Modifier
) {
    val hotness = ((peer?.rssi ?: -100) + 100).coerceIn(0, 100) / 100f
    // Signal strength still tints the ring, from cold blue to warm green. It no
    // longer drives any lit segment: the ring only lights up for a bearing.
    val defaultRingColor = Color.lerp(Color(0xFF3F51B5), Color(0xFF00C853), hotness)
    val ringColor = beacon?.color ?: defaultRingColor

    // Fill the width, but stay inside it: the caller measures this composable
    // before it is laid out, so onSizeChanged would give 0 for the first frame.
    // Constraining to the parent width and deriving the diameter from it avoids
    // a fixed height that the surrounding text can overflow.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth
        val radarDiameter = if (available == Dp.Unspecified || available <= 0.dp) {
            240.dp
        } else {
            // Leave a margin so the ring never touches the screen edge, and cap
            // it so the radar does not become enormous on a tablet.
            (available * 0.82f).coerceIn(160.dp, 320.dp)
        }

        // The radar is sized from the width, so on most phones the ring's square
        // is shorter than the room the column has, and whatever is left over
        // lands all on one side. That is the asymmetry this fixes: with the gap
        // only below the ring - the spacer here plus the button row's own top
        // padding - the ring sat pressed up under the app bar while an equal
        // amount of air showed underneath it.
        //
        // Derived from the ring's own diameter rather than fixed, so the two
        // gaps stay equal to each other *and* keep their proportion when the
        // radar grows on a tablet. A fixed spacer would match on one screen size
        // and drift on every other.
        val verticalBuffer = (radarDiameter * 0.05f).coerceIn(8.dp, 20.dp)

        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // The beacon's title used to be repeated here in a chip above the ring.
        // A beacon is already named in the banner when it is your own and in the
        // chat entry for every beacon, and the ring itself is tinted, so the chip
        // was a third copy of the same words sitting on top of the other two.
        // A remote peer's beacon is still legible: the ring carries its colour.

        Spacer(Modifier.height(verticalBuffer))
        if (hasPeers && (peer != null)) {
            Text(
                peer.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
        }
        ProximityRing(
            color = ringColor,
            arrowDegrees = peer?.azimuth,
            distanceMeters = peer?.distance,
            beacon = beacon,
            accessibilityDescription = RadarAccessibilityPolicy.describe(
                peerName = peer?.name,
                distanceMeters = peer?.distance,
                azimuthDegrees = peer?.azimuth,
                proximity = peer?.proximity,
                beaconTitle = beacon?.title,
            ),
            accessibilityEnabled = radarScreenReaderSummaryEnabled,
            // Sized from the available width rather than fixed. The caller used
            // to pin this column to 260.dp while the ring inside it was also
            // 260.dp, so the name above and the distance readout below had
            // nowhere to go: the Column overflowed and the ring was clipped top
            // and bottom. Deriving the diameter from the width leaves room for
            // the surrounding text and still fits a narrow phone.
            modifier = Modifier.size(radarDiameter)
        )
        // The same buffer below the ring as above it. When a peer is tracked the
        // distance readout follows in this space instead, which keeps the ring
        // itself optically centred between the two.
        Spacer(Modifier.height(verticalBuffer))
        if (peer != null) {
            Text(
                peer.distance?.let { "%.1f m away".format(it) } ?: "${peer.proximity} signal",
                style = MaterialTheme.typography.headlineSmall,
                color = ringColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (peer.azimuth != null) "Direction locked · ${peer.azimuth.toInt()}°" else "BLE proximity mode",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
    }
}

@Composable
private fun ProximityRing(
    color: Color,
    arrowDegrees: Float?,
    distanceMeters: Float?,
    beacon: BeaconInfo?,
    accessibilityDescription: String,
    accessibilityEnabled: Boolean,
    modifier: Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "beaconPulse")
    val beaconPulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseRadius"
    )
    val beaconPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseAlpha"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (accessibilityEnabled) Modifier.semantics { contentDescription = accessibilityDescription }
                    else Modifier
                )
                // The beacon pulse is deliberately drawn larger than the ring
                // (see beaconPulseRadius above), so without this it escapes the
                // composable's bounds and paints over whatever sits above the
                // radar - the top bar, in this layout. Clipping keeps the
                // overspill as a visual fade at the edge rather than an
                // overlay across the app bar.
                .clipToBounds()
        ) {
            // One source of truth for the ring's stroke, radius and origin. The
            // lit arc below is inscribed in the very same square, so the two
            // cannot drift apart. Half the stroke is inset from the canvas edge
            // so the whole band fits inside it.
            val ring = RadarDirectionPolicy.ringGeometry(size.width, size.height)
            val stroke = ring.strokePx
            val ringRadius = ring.radiusPx
            val center = Offset(size.width / 2, size.height / 2)

            // Pulsing beacon wave if active. The animated radius is clamped to
            // the drawable extent: the wave still grows as it fades, but it
            // reaches the edge rather than passing through it, so the pulse
            // reads as expanding outwards without leaving the canvas.
            if (beacon != null) {
                drawCircle(
                    color = color.copy(alpha = beaconPulseAlpha),
                    radius = ringRadius * beaconPulseRadius.coerceAtMost(1f),
                    style = Stroke(stroke * 0.7f)
                )
            }

            // The base ring. radius must be given explicitly: drawCircle
            // defaults it to half the smaller canvas dimension, which puts the
            // centre line of the stroke exactly on the canvas edge and clips
            // the outer half of it away - leaving the chamfered-square shape
            // rather than a circle. Subtracting the stroke width pulls the
            // centre line in by half a stroke, so the full width fits.
            drawCircle(
                color = color.copy(alpha = if (beacon != null) 0.25f else .12f),
                radius = ringRadius,
                style = Stroke(stroke)
            )

            // The directional segment: a lit arc of the ring pointing at the
            // tracked member, widening as they approach.
            //
            // There is deliberately no fallback for a missing bearing. An earlier
            // version drew the old signal-strength arc instead, which put a lit
            // highlight on the ring whenever nobody had joined - and because the
            // sweep was floored at 8%, it drew one even at zero signal. With a
            // stroke 8% of the canvas and a round cap, that read as a stray blob
            // rather than as "no direction known". An unlit ring is the honest
            // state: no bearing means there is nothing to point at.
            val segment = RadarDirectionPolicy.segment(arrowDegrees, distanceMeters)
            if (segment != null) {
                if (segment.isFullyLit) {
                    // Within arms reach: the whole ring is lit rather than a
                    // 360-degree round-capped arc, which would render as a
                    // heavy blob at the seam. radius given explicitly for the
                    // same reason as the base ring.
                    drawCircle(color, radius = ringRadius, style = Stroke(stroke))
                } else {
                    drawArc(
                        color = color,
                        startAngle = segment.startAngleDegrees,
                        sweepAngle = segment.sweepAngleDegrees,
                        useCenter = false,
                        // Inscribed in the ring's own square. An oval's centre
                        // is its top-left plus half its size, so passing
                        // (ringRadius, ringRadius) - as this did, and as the
                        // canvas-sized default would have too - puts the arc's
                        // centre at the canvas' bottom-right corner region
                        // instead of its middle: the highlight landed beside
                        // the ring and was clipped by the canvas edge.
                        topLeft = Offset(ring.topLeftXPx, ring.topLeftYPx),
                        size = Size(ring.arcSizePx, ring.arcSizePx),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }

            // The needle always points somewhere. With no bearing - nobody
            // joined, no member selected, or the member is tracked by RSSI
            // alone - it rests pointing straight up rather than vanishing, so
            // the radar reads as idle-and-ready rather than broken. A missing
            // bearing is not a bearing of north, so it is drawn dimmed to show
            // it is resting rather than pointing at someone.
            //
            // Drawn here rather than as a fixed-size child Box: the ring is
            // sized from the available width, so a hardcoded 104.dp needle
            // spanned only a third of it on a phone and was swallowed entirely
            // by the hub in the middle. Every length below comes off the ring's
            // own geometry for the same reason.
            val needleRotation = RadarDirectionPolicy.needleRotationDegrees(arrowDegrees) ?: 0f
            val hasBearing = arrowDegrees != null && arrowDegrees.isFinite()
            val needleColor = if (hasBearing) color else color.copy(alpha = 0.45f)
            val radians = Math.toRadians(needleRotation.toDouble())
            val needleTip = Offset(
                x = center.x + (sin(radians) * ring.needleLengthPx).toFloat(),
                y = center.y - (cos(radians) * ring.needleLengthPx).toFloat(),
            )
            // Starts at the edge of the hub rather than the very centre, so the
            // hub dot reads as the pivot and not as part of the needle.
            val needleBase = Offset(
                x = center.x + (sin(radians) * ring.needleBaseRadiusPx).toFloat(),
                y = center.y - (cos(radians) * ring.needleBaseRadiusPx).toFloat(),
            )
            drawLine(
                color = needleColor,
                start = needleBase,
                end = needleTip,
                strokeWidth = ring.needleStrokePx,
                cap = StrokeCap.Round,
            )
            drawCircle(needleColor, ring.needleTipRadiusPx, needleTip)

            // The hub, drawn last so it sits over the needle's base.
            //
            // Previously a 74.dp Box layered over the Canvas. A fixed size cannot
            // work here: the radar's diameter ranges from 160.dp to 320.dp with
            // the available width, and at the small end a 74.dp hub was as wide
            // as the ring's radius, hiding the middle of the radar. As a fraction
            // of the ring it holds its proportion at every size.
            drawCircle(
                color = color.copy(alpha = if (beacon != null) .30f else .14f),
                radius = ring.hubRadiusPx,
            )
        }
    }
}

/**
 * The member list beside (or below) the radar.
 *
 * Internal rather than private so a test can compose it directly. The
 * surrounding [RadarScreen] is only reachable with a null
 * [com.fkurevija.pingring.data.DiscoveryRepository], which means no peers - and
 * the list is where a member actually appears, gets marked as tracked, and
 * shows its beacon. Testing it here means handing over a
 * [com.fkurevija.pingring.data.RadarPeerUpdate] directly, rather than standing up
 * a transport and waiting for a real peer to show up.
 */
@Composable
internal fun PeerList(
    peers: List<RadarPeerUpdate>,
    connectivity: ConnectivityStatus,
    repository: DiscoveryRepository?,
    activeBeacons: Map<String, BeaconInfo>,
    focusedPeerId: String?,
    canSelect: Boolean,
    onFocusPeer: (String?) -> Unit,
    modifier: Modifier
) {
    Column(modifier.padding(bottom = 8.dp)) {
        if (!connectivity.isBluetoothEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bluetooth is off. Enable it to discover nearby peers.")
                    IconButton(onClick = { repository?.refreshConnectivityStatus() }) {
                        Icon(Icons.Default.Visibility, contentDescription = "Recheck")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isFull = (peers.size + 1) >= MAX_PARTICIPANTS
            Text(
                if (isFull) "Members (${peers.size + 1}/${MAX_PARTICIPANTS} - Full)" else "Members (${peers.size + 1}/${MAX_PARTICIPANTS})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        // The one member being tracked, as a control rather than only as a
        // highlight on a row. The per-row buttons below can already set this,
        // but they cannot show what is currently tracked without reading the
        // list, and they give no way back to "whichever the policy picks" once
        // something has been chosen. Disabled rather than hidden when the host
        // owns the choice, so the radar is never pointed at somebody with no
        // visible explanation of why.
        if (peers.isNotEmpty()) {
            TrackedMemberPicker(
                peers = peers,
                trackedPeerId = focusedPeerId,
                canSelect = canSelect,
                onSelect = onFocusPeer
            )
            Spacer(Modifier.height(8.dp))
        }
        if (peers.isEmpty()) {
            Text(
                "Your ephemeral ID is private to this event. When someone appears, their signal and direction will show here.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.peerId }) { peer ->
                    val peerBeacon = activeBeacons[peer.peerId]
                    val isFocused = peer.peerId == focusedPeerId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = when {
                            peerBeacon != null ->
                                CardDefaults.cardColors(containerColor = peerBeacon.color.copy(alpha = 0.12f))
                            isFocused ->
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ->
                                CardDefaults.cardColors()
                        }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val indicatorColor = if (peerBeacon != null) {
                                peerBeacon.color
                            } else if (peer.proximity == "Hot") {
                                Color(0xFF00C853)
                            } else {
                                Color(0xFF3F51B5)
                            }
                            Box(Modifier.size(12.dp).background(indicatorColor, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(peer.name, fontWeight = FontWeight.SemiBold)
                                    if (isFocused) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "TRACKING",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (peerBeacon != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "🚨 ${peerBeacon.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = peerBeacon.color,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text("${peer.proximity} · ${peer.rssi ?: 0} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(peer.distance?.let { "%.1f m".format(it) } ?: "—", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            // Tapping the row makes this the one member the
                            // radar draws and the UWB session ranges. Tapping
                            // the tracked member again returns to auto, so the
                            // choice is always reversible without leaving the
                            // screen.
                            //
                            // Disabled under a host-owned policy rather than
                            // hidden, so the row still shows who is being
                            // tracked and why the control is inert.
                            IconButton(
                                onClick = { onFocusPeer(if (isFocused) null else peer.peerId) },
                                enabled = canSelect,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isFocused) Icons.Default.MyLocation else Icons.Default.GpsFixed,
                                    contentDescription = if (isFocused) "Stop tracking ${peer.name}" else "Track ${peer.name}",
                                    tint = if (isFocused) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
/**
 * Names the one member currently being tracked, and lets it be changed.
 *
 * A dropdown rather than another row button because the tracked member is a
 * property of the whole radar, not of a row: the radar draws this person, the
 * UWB session spends itself on them, and proximity haptics fire for them. The
 * per-row buttons in [PeerList] set the same value, and both stay in place -
 * this one answers "who am I following right now" without counting badges down
 * a scrolling list, and offers a way back to the automatic choice.
 *
 * [canSelect] is false only where the host owns the decision, and the field
 * stays visible but disabled in that case. Hiding it would leave the radar
 * locked onto the host with nothing on screen to say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackedMemberPicker(
    peers: List<RadarPeerUpdate>,
    trackedPeerId: String?,
    canSelect: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val trackedName = peers.firstOrNull { it.peerId == trackedPeerId }?.name
    val label = when {
        trackedName != null -> trackedName
        // No explicit choice: whatever FocusPolicy resolved is being tracked,
        // which is not necessarily in this list - under a host-owned policy the
        // guest tracks the host, and the host is not its own peer.
        else -> "Automatic"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (canSelect) expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = canSelect,
            label = { Text("Tracking") },
            leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Back to the automatic choice. Needed because picking somebody is
            // one-way otherwise: the row buttons clear a selection, but this
            // control is how the choice is inspected, so it has to be where it
            // can also be undone.
            DropdownMenuItem(
                text = { Text("Automatic") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            peers.forEach { peer ->
                DropdownMenuItem(
                    text = { Text(peer.name) },
                    onClick = {
                        onSelect(peer.peerId)
                        expanded = false
                    }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconCustomizationDialog(
    localBeacon: BeaconInfo?,
    quickBeacon: QuickBeacon?,
    onDismiss: () -> Unit,
    onActivate: (title: String, colorArgb: Long, patternName: String) -> Unit,
    onQuickBeaconChange: (QuickBeacon?) -> Unit,
    onStop: () -> Unit,
    onTestVibrate: (LongArray) -> Unit
) {
    var isVibrationMenuExpanded by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(BeaconPreset.entries.first()) }
    // Prefills from what is live, else from the set Quick Beacon, else the
    // first preset: whichever the user is most likely to want on screen first.
    var title by remember { mutableStateOf(localBeacon?.title ?: quickBeacon?.title ?: selectedPreset.defaultTitle) }
    var selectedColor by remember { mutableLongStateOf(localBeacon?.colorArgb ?: quickBeacon?.colorArgb ?: selectedPreset.defaultColorArgb) }
    var selectedPattern by remember {
        mutableStateOf(
            localBeacon?.let { BeaconVibrationPattern.fromName(it.vibrationPattern) }
                ?: quickBeacon?.pattern
                ?: selectedPreset.defaultPattern
        )
    }

    // The selection in the same shape as the saved Quick Beacon, so "is this
    // already the Quick Beacon?" is one comparison rather than three - and the
    // title is normalised here, so what gets set is exactly what Activate sends.
    val currentSelection = QuickBeacon(
        title = title.ifBlank { DEFAULT_BEACON_TITLE },
        colorArgb = selectedColor,
        pattern = selectedPattern
    )
    val isQuickBeaconSet = currentSelection == quickBeacon

    val colorPalette = listOf(
        0xFFFF1744, // Red
        0xFFFF9100, // Amber
        0xFF00E676, // Green
        0xFF00E5FF, // Cyan
        0xFFD500F9  // Purple
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = Color(selectedColor),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (localBeacon != null) "Update Active Beacon" else "Create Beacon")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select Preset", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BeaconPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                title = preset.defaultTitle
                                selectedColor = preset.defaultColorArgb
                                selectedPattern = preset.defaultPattern
                                isVibrationMenuExpanded = false // Close menu on selection
                            },
                            label = { Text("${preset.iconEmoji} ${preset.label}") }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Beacon Message / Intent") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )

                Text("Beacon Color", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorPalette.forEach { colorArgb ->
                        val isSelected = selectedColor == colorArgb
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(colorArgb), CircleShape)
                                .clickable { selectedColor = colorArgb }
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Text("Vibration Rhythm & Intensity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                ExposedDropdownMenuBox(
                    expanded = isVibrationMenuExpanded,
                    onExpandedChange = { isVibrationMenuExpanded = !isVibrationMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedPattern.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVibrationMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = isVibrationMenuExpanded,
                        onDismissRequest = { isVibrationMenuExpanded = false }
                    ) {
                        BeaconVibrationPattern.entries.forEach { pattern ->
                            DropdownMenuItem(
                                text = { Text(pattern.displayName) },
                                onClick = {
                                    selectedPattern = pattern
                                    isVibrationMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onTestVibrate(selectedPattern.pattern) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test Vibration")
                }

                // The configuration the Quick Beacon button sends. One slot,
                // so this both saves the current selection and clears it; the
                // label says which, and the star fills to show the state.
                //
                // Named for what it is for rather than a generic "favourite":
                // the label has to match the button on the radar that reads it
                // back, or the two stop describing the same thing.
                OutlinedButton(
                    onClick = { onQuickBeaconChange(if (isQuickBeaconSet) null else currentSelection) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (isQuickBeaconSet) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isQuickBeaconSet) "Remove Quick Beacon" else "Set as Quick Beacon")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onActivate(currentSelection.title, currentSelection.colorArgb, currentSelection.pattern.name)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(selectedColor))
            ) {
                Text(if (localBeacon != null) "Update Beacon" else "Activate Beacon", color = Color.White)
            }
        },
        dismissButton = {
            Row {
                if (localBeacon != null) {
                    TextButton(
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop Beacon")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun Color.Companion.lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
