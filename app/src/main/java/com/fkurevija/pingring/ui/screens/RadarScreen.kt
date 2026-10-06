package com.fkurevija.pingring.ui.screens
import com.fkurevija.pingring.data.TextInputPolicy
import com.fkurevija.pingring.data.RadarFeedbackPolicy
import com.fkurevija.pingring.data.RadarAnnouncementPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.fkurevija.pingring.ui.accessibility.rememberAccessibilityEnvironment

import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.BeaconPreset
import com.fkurevija.pingring.data.BeaconAlertPolicy
import com.fkurevija.pingring.data.BeaconAlertRepeat
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.BEACON_REPEAT_DURATION_UNTIL_STOPPED
import com.fkurevija.pingring.data.BeaconVibrationPattern
import com.fkurevija.pingring.data.ChatMessage
import com.fkurevija.pingring.data.ChatBubbleColorPolicy
import com.fkurevija.pingring.data.ChatMessageOwnership
import com.fkurevija.pingring.data.ChatBubblePalette
import com.fkurevija.pingring.data.ChatTimelineItem
import com.fkurevija.pingring.data.ChatTimelinePolicy
import com.fkurevija.pingring.data.ConnectionRecoveryPromptPolicy
import com.fkurevija.pingring.data.ConnectivityStatus
import com.fkurevija.pingring.data.DEFAULT_BEACON_TITLE
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy
import com.fkurevija.pingring.data.LocalRadioAdvicePolicy.Advice
import com.fkurevija.pingring.data.PeerListOrderPolicy
import com.fkurevija.pingring.data.FocusPolicy
import com.fkurevija.pingring.data.LastKnownPeerDirection
import com.fkurevija.pingring.data.MAX_PARTICIPANTS
import com.fkurevija.pingring.data.PendingJoinRequest
import com.fkurevija.pingring.data.RadarAccessibilityPolicy
import com.fkurevija.pingring.data.RadarDirectionPolicy
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.data.SessionState
import com.fkurevija.pingring.R
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.atan2
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
    coordinationError: String?,
    discoveryRepository: DiscoveryRepository?,
    sessionState: SessionState,
    chatBubbleColorArgb: Long?,
    onChatBubbleColorChange: (Long) -> Unit,
    /**
     * Overrides for the chat-colour state the screen would otherwise read from the
     * repository.
     *
     * Present because the repository is null in the screenshot tests, and a colour
     * somebody else holds is the only interesting case here - without an override
     * every test would see a ring of one and every swatch free, which is precisely
     * the state that cannot show the feature working. Null means "read it from the
     * repository", which is what the real app does.
     */
    chatColourHoldersOverride: Map<Long, String>? = null,
    refusedChatColourArgb: Long? = null,
    /**
     * Our own peer id, when the repository that knows it is absent.
     *
     * The app always has one, so this exists only so a test can say who "us" is:
     * without it the picker cannot tell our own colour from somebody else's, and
     * greys out the wrong one. Null means "read it from the repository".
     */
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
    var showChatSettings by remember { mutableStateOf(false) }
    // The colour the host last refused, so the dialog can say so. Null when
    // nothing was refused, which is what a member sees almost always - it is only
    // set for the round trip after a refused pick, and cleared by the next one.
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
        if (isHost || !isTopDestination || !activityResumed || !connectionRecoveryPromptEnabled ||
            connectionPromptSilenced
        ) return@LaunchedEffect
        val hostLostAtMillis = hostConnectionLostAtMillis

        val dueAtMillis = ConnectionRecoveryPromptPolicy.dueAtMillis(
            hostConnectionLostAtMillis = hostLostAtMillis,
            configuredDelayMillis = connectionRecoveryPromptDelayMillis,
            snoozedUntilMillis = connectionLostSnoozedUntilMillis,
        ) ?: return@LaunchedEffect
        delay((dueAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
        // Deliberately not also re-reading the repository here. The host-lost
        // instant arrives as a parameter precisely so this screen can be driven
        // by it alone, and ANDing in a second copy of the same state silently
        // kills the prompt for any caller that does not also hand over a
        // DiscoveryRepository - the prompt then simply never appears. Losing the
        // connection is already handled above: the caller clears
        // hostConnectionLostAtMillis, which restarts this effect and returns.
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
    // Deliberately no onDispose-triggered stopDiscovery() here: HostSetupScreen
    // and RadarScreen share the same DiscoveryRepository instance for the
    // lifetime of an event (e.g. navigating to the event settings screen and
    // back must not drop the connection). Discovery is only stopped
    // explicitly when the user actually leaves/ends the event, via
    // onLeaveEvent/onEndEvent in MainActivity.

    // Members, not radar readings. The member list and the count beside it are
    // rendered from this; `peers` below is the live radar cache and is what the
    // pins are drawn from. Using the cache for the list made the list empty
    // whenever contact lapsed, so two devices in one ring could each show a
    // single member - themselves - while both were still counted in the ring.
    val peers by discoveryRepository?.members?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<RadarPeerUpdate>()) }
    val connectivity by discoveryRepository?.connectivityStatus?.collectAsState()
        ?: remember { mutableStateOf(ConnectivityStatus(isBluetoothEnabled = true, isWifiEnabled = true)) }
    val activeBeacons by discoveryRepository?.activeBeacons?.collectAsState()
        ?: remember { mutableStateOf(emptyMap()) }
    // Which chat bubble colour each member holds, so the picker can grey out the
    // ones that are taken and name who has them.
    //
    // Read from the repository in the app. The override exists because the
    // screenshot tests pass no repository, and a ring of one is exactly the state
    // that cannot show this feature working - so without it every test would see
    // twelve free swatches.
    val repositoryHolders by discoveryRepository?.chatColourHolders?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<Long, String>()) }
    val chatColourHolders = chatColourHoldersOverride ?: repositoryHolders
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

    // How this device wants to be alerted. Seeded to the stored default so the
    // beacon sheet always shows a real choice rather than a spinner on the way
    // in; the repository replaces it as soon as DataStore answers.
    val beaconAlertSettings by discoveryRepository?.beaconAlertSettings?.collectAsState()
        ?: remember { mutableStateOf(BeaconAlertSettings.DEFAULT) }

    // Which beacons this device has muted. Local only: never sent to the sender
    // or to any other member.
    val silencedBeaconIds by discoveryRepository?.silencedBeaconIds?.collectAsState()
        ?: remember { mutableStateOf(emptySet<String>()) }
    val haptic = LocalHapticFeedback.current
    // For the one toast on this screen, which has to fire from inside a click
    // handler rather than from a LaunchedEffect, so it needs a context captured
    // here rather than one read at the call site.
    val context = LocalContext.current
    val accessibilityEnvironment = rememberAccessibilityEnvironment(reduceMotionEnabled)

    // Who we are, resolved once. This was read from the repository in four places
    // and from the repository-or-override in a fifth, so the screen could hold
    // two different answers to "am I this message's sender" at the same time -
    // which is precisely the kind of disagreement that shows up as a message
    // drawn on the wrong side. One value, used everywhere.
    val localPeerId = localPeerIdOverride ?: discoveryRepository?.localPeerId

    // The focused member, chosen by the user or defaulted by FocusPolicy. The
    // radar draws only this person, which is what makes a full 8-member
    // PingRing readable, and the UWB session follows the same choice.
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
    LaunchedEffect(lastKnownDirection?.peerId) {
        if (lastKnownDirection != null) {
            while (true) {
                delay(5_000L)
                lastKnownClockMillis = System.currentTimeMillis()
            }
        }
    }

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
        RadarAccessibilityPolicy.describeLocalized(
            resources = context.resources,
            peerName = focusedPeer?.name,
            distanceMeters = focusedPeer?.distance,
            azimuthDegrees = focusedPeer?.azimuth,
            proximityLabel = focusedPeer?.proximity?.let { proximity ->
                context.getString(when (proximity) {
                    "Hot" -> R.string.proximity_hot
                    "Cold" -> R.string.proximity_cold
                    else -> R.string.proximity_unknown
                })
            },
            beaconTitle = focusedBeacon?.title,
            distanceIsEstimate = focusedPeer?.distanceSource == com.fkurevija.pingring.data.RangingSourcePolicy.RangingSource.GNSS,
            distanceAccuracyMeters = focusedPeer?.distanceAccuracyMeters,
        ) + if (focusedPeer == null && focusedBeacon != null) {
            context.getString(R.string.radar_active_beacon, focusedBeacon.title)
        } else ""
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
            TopAppBar(
                title = {
                    // No app name here. Every member is looking at this screen
                    // inside the same app, so "PingRing" told them nothing they
                    // did not already know while pushing the code and the member
                    // count down. What the title actually has to carry is the two
                    // things that are unique to this moment: which ring this is,
                    // and how full it is.
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "Code: $eventId",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val isFull = (peers.size + 1) >= MAX_PARTICIPANTS
                        Text(
                            text = stringResource(
                                if (isFull) R.string.member_count_full else R.string.member_count,
                                peers.size + 1,
                                MAX_PARTICIPANTS
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showEndConfirmation = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (isHost) R.string.end_pingring else R.string.leave_pingring)
                        )
                    }
                },
                actions = {
                    // The member list, on every screen size.
                    //
                    // On a tablet the peers are a permanent second pane, so this
                    // button repeats what is already on screen. It is kept
                    // regardless, because one predictable control is easier to
                    // find and to document than one that only sometimes exists,
                    // and because on a phone it is the only way to see who is in
                    // the ring at all.
                    IconButton(onClick = { showMemberSheet = true }) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = stringResource(R.string.show_members)
                        )
                    }
                    val acceptsNewMembers = sessionState.currentEvent?.acceptsNewMembers ?: true
                    if (acceptsNewMembers) {
                        IconButton(onClick = { showQrCode = true }) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = stringResource(R.string.show_join_qr)
                            )
                        }
                    }
                    if (isHost) {
                        TextButton(onClick = onEventSettings) { Text(stringResource(R.string.settings)) }
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

            val weakSignalPeers = peers.filter { it.signalWeak }
            val trackedWeakPeer = focusedPeer?.takeIf { it.signalWeak }
            val weakSignalMessage = when {
                trackedWeakPeer != null -> stringResource(
                    R.string.radar_weak_tracked_signal,
                    trackedWeakPeer.name,
                )
                weakSignalPeers.isNotEmpty() -> stringResource(
                    R.string.radar_weak_group_signal,
                    weakSignalPeers.size,
                )
                else -> null
            }
            weakSignalMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            lastKnownDirection?.let { location ->
                val bearingDegrees = (
                    Math.toDegrees(atan2(location.worldDirectionX.toDouble(), location.worldDirectionY.toDouble()))
                        .toInt() % 360 + 360
                    ) % 360
                val secondsAgo = ((lastKnownClockMillis - location.lastDetectedAtMillis).coerceAtLeast(0L) / 1_000L)
                    .toInt()
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                R.string.radar_last_known_direction,
                                location.name,
                                bearingDegrees,
                                secondsAgo,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
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
                            pluralStringResource(R.plurals.join_request_count, requestsToShow.size, requestsToShow.size),
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
                                    Text(stringResource(R.string.deny))
                                }
                                Spacer(Modifier.width(4.dp))
                                Button(onClick = { onApproveJoinRequest(request.endpointId) }) {
                                    Text(stringResource(R.string.allow))
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
                                    stringResource(R.string.your_beacon_is_active, beacon.title),
                                    fontWeight = FontWeight.Bold,
                                    color = beacon.color
                                )
                                Text(
                                    stringResource(R.string.calling_nearby_peers),
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
                            Text(stringResource(R.string.stop))
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
                            accessibilityEnvironment.reduceMotion,
                            spokenUpdateText,
                            Modifier.width(paneWidth)
                        )
                        PeerList(
                            peers,
                            connectivity,
                            discoveryRepository,
                            activeBeacons,
                            focusedPeerId = focusSelection,
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

                    // The newest colour each sender has been seen using, across
                    // the whole transcript. Built from the messages rather than
                    // tracked per send as they arrive, so repainting an old
                    // bubble needs no extra bookkeeping: every bubble asks this
                    // map who somebody is *now*, not what they were when they
                    // typed.
                    val latestSenderColors = remember(sessionState.chatMessages) {
                        ChatBubbleColorPolicy.latestColorsBySender(sessionState.chatMessages)
                    }

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
                                    stringResource(R.string.chat),
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
                                                    stringResource(R.string.no_messages_yet),
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
                                                    localPeerId
                                                )
                                                // "The colour this person is
                                                // using now", not the one the
                                                // message carried when it was sent.
                                                // See ChatBubbleColorPolicy:
                                                // without this a member who changes
                                                // colour has their own transcript
                                                // split down the middle.
                                                val senderBubbleColor = ChatBubbleColorPolicy
                                                    .colorFor(
                                                        message = msg,
                                                        localPeerId = localPeerId,
                                                        localColorArgb = chatBubbleColorArgb,
                                                        latestColorByPeer = latestSenderColors,
                                                    )
                                                    ?.let(::Color)
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

                                                is ChatTimelineItem.Beacon -> ActiveBeaconEntry(
                    beacon = entry.beacon,
                    silenced = entry.beacon.id in silencedBeaconIds,
                    onToggleSilenced = { silenced ->
                        discoveryRepository?.setBeaconSilenced(entry.beacon.id, silenced)
                    },
                )
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
                                        onValueChange = { chatText = TextInputPolicy.sanitizeMessage(it) },
                                        placeholder = { Text(stringResource(R.string.type_message), style = MaterialTheme.typography.bodyMedium) },
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
                                            contentDescription = stringResource(R.string.send),
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
                                accessibilityEnvironment.reduceMotion,
                                spokenUpdateText,
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
                                // Chat settings on the left, the beacon on the right, one
                                // row. They used to be stacked, each filling the width, which
                                // spent two rows of a short screen on two short controls
                                // and pushed the chat transcript down for both.
                                //
                                // The order is deliberate rather than arbitrary: the left is
                                // how the conversation looks and the right is what the
                                // conversation is saying about where you are.
                                //
                                // Equal halves, and the labels are allowed to wrap to a
                                // second line rather than being clipped. German
                                // "Chat-Einstellungen" and Canadian French "Réglages du
                                // clavardage" do not fit half a phone width on one line, and
                                // an ellipsised button label in one language and not another
                                // is worse than a button that is briefly taller.
                                OutlinedButton(
                                    onClick = { showChatSettings = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.chat_settings),
                                        textAlign = TextAlign.Center,
                                    )
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
                                    Text(
                                        stringResource(if (localBeacon != null) R.string.my_beacon else R.string.new_beacon),
                                        textAlign = TextAlign.Center,
                                    )
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

                if (showConnectionLostDialog) {
                    AlertDialog(
                        onDismissRequest = ::snoozeConnectionLostPrompt,
                        title = { Text(stringResource(R.string.connection_lost_title)) },
                        text = {
                            val delayMinutes = ((connectionRecoveryPromptDelayMillis + 59_999L) / 60_000L).toInt()
                            Text(stringResource(R.string.connection_lost_message, delayMinutes))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showConnectionLostDialog = false
                                    onLeaveEvent()
                                }
                            ) { Text(stringResource(R.string.leave_pingring)) }
                        },
                        dismissButton = {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = ::snoozeConnectionLostPrompt) {
                                    Text(stringResource(R.string.connection_lost_snooze))
                                }
                                TextButton(
                                    onClick = {
                                        connectionPromptSilenced = true
                                        showConnectionLostDialog = false
                                    }
                                ) { Text(stringResource(R.string.connection_lost_silence)) }
                            }
                        },
                    )
                }

                if (showChatSettings) {
                    ChatSettingsDialog(
                        selectedColorArgb = chatBubbleColorArgb,
                        // Routed through the claim rather than applied directly: a
                        // guest's pick is a request the host has to agree to, and
                        // applying it optimistically would show a colour for as long
                        // as the round trip took - which is the window in which two
                        // members think they share one.
                        onColorSelected = { requested ->
                            val repository = discoveryRepository
                            if (repository == null) {
                                onChatBubbleColorChange(requested)
                            } else {
                                repository.requestChatColour(requested) { granted ->
                                    // Said out loud on a refusal rather than left
                                    // to the greyed swatch: a member who tapped
                                    // and nothing happened has no other way to
                                    // learn it was refused.
                                    refused = if (granted) null else requested
                                    if (granted) onChatBubbleColorChange(requested)
                                }
                            }
                        },
                        onDismiss = { showChatSettings = false },
                        colourHolders = chatColourHolders,
                        memberNameFor = { peerId ->
                            peers.firstOrNull { it.peerId == peerId }?.name ?: peerId
                        },
                        localPeerId = localPeerId,
                        refusedColorArgb = refused ?: refusedChatColourArgb,
                    )
                }

                if (showBeaconSheet) {
                    BeaconCustomizationDialog(
                        localBeacon = localBeacon,
                        onDismiss = { showBeaconSheet = false },
                        onActivate = { title, colorArgb, patternName ->
                            discoveryRepository?.activateBeacon(title, colorArgb, patternName, senderName)
                        },
                        onStop = {
                            discoveryRepository?.cancelLocalBeacon()
                        },
                        onTestVibrate = { pattern ->
                            discoveryRepository?.playVibrationPattern(pattern)
                        }
                    )
                }

                if (showMemberSheet) {
                    // A sheet rather than a screen, deliberately. The peers are a
                    // view onto the radar, not a place of their own: they answer
                    // "who is here" and are worth one gesture, not a navigation
                    // step that has to be undone before you can see the radar
                    // again.
                    //
                    // Fully expanded rather than half-height, because PeerList
                    // opens with its connectivity warnings when Bluetooth is off
                    // and a half-height sheet buries the count and the rows under
                    // them. Opening the member list has to actually show the
                    // member list, which is the whole reason this control exists.
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = { showMemberSheet = false },
                        sheetState = sheetState,
                    ) {
                        // No heading of our own: PeerList already states the
                        // count, and it states it differently when the ring is
                        // full. A second copy here would disagree with it the
                        // moment the count changed underneath.
                        PeerList(
                            peers = peers,
                            connectivity = connectivity,
                            repository = discoveryRepository,
                            activeBeacons = activeBeacons,
                            focusedPeerId = focusSelection,
                            onFocusPeer = { discoveryRepository?.setFocusedPeer(it) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }

                if (showQrCode) {
                    AlertDialog(
                        onDismissRequest = { showQrCode = false },
                        title = { Text(stringResource(R.string.scan_to_join)) },
                        text = {
                            // The same block Host Setup shows, so the invitation a guest is given
                            // looks the same before the radar as it does during it. Only the
                            // QR code is drawn larger here: this is a dialog with room to
                            // spare, and a guest scanning it across a table wants the
                            // modules big.
                            PingRingCodeBlock(
                                eventCode = eventId,
                                inviteSecret = sessionState.currentEvent?.inviteSecret,
                                qrSize = 200.dp,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showQrCode = false }) { Text(stringResource(R.string.close)) }
                        }
                    )
                }
            }
        }
    }
}

/**
 * One local-radio notice, at the top of the member list.
 *
 * Every notice says what is *lost* rather than what is broken, because none of
 * them stops the ring working - PingRing is meant to run with no network at all.
 * See [LocalRadioAdvicePolicy] for why that constraint is load-bearing.
 */
@Composable
private fun LocalRadioAdviceCard(
    advice: Advice,
    isError: Boolean,
    onRecheck: () -> Unit,
) {
    val text = when (advice) {
        Advice.BLUETOOTH_OFF -> stringResource(R.string.bluetooth_off_discovery)
        Advice.WIFI_OFF -> stringResource(R.string.wifi_off_bluetooth_only)
        Advice.BLE_IDENTITY_UNAVAILABLE -> stringResource(R.string.ble_identity_unavailable)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (advice) {
                    Advice.WIFI_OFF -> Icons.Default.WifiOff
                    else -> Icons.Default.BluetoothDisabled
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(text, modifier = Modifier.weight(1f))
            IconButton(onClick = onRecheck) {
                Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.recheck))
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
 *
 * Internal rather than private so a test can compose it directly, for the same
 * reason [PeerList] is: reaching this through [RadarScreen] needs a
 * repository, and the thing worth testing here is that silencing changes the
 * alarm control WITHOUT removing the beacon from the transcript.
 */
@Composable
internal fun ActiveBeaconEntry(
    beacon: BeaconInfo,
    silenced: Boolean,
    onToggleSilenced: (Boolean) -> Unit,
) {
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
            contentDescription = stringResource(R.string.active_beacon),
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
                stringResource(
                    R.string.beacon_sender_pattern,
                    beacon.senderName,
                    stringResource(BeaconVibrationPattern.fromName(beacon.vibrationPattern).labelRes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Stated in words as well as shown by the icon, because the icon is
            // the only signal on this row that changes and a member who has just
            // silenced something needs to be able to confirm it took.
            if (silenced) {
                Text(
                    stringResource(R.string.beacon_silenced_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = { onToggleSilenced(!silenced) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (silenced) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                // Names both the action and what it means, so the state is not
                // carried by the icon shape alone.
                contentDescription = stringResource(
                    if (silenced) R.string.unsilence_beacon else R.string.silence_beacon,
                    beacon.title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RadarContent(
    peer: RadarPeerUpdate?,
    hasPeers: Boolean,
    beacon: BeaconInfo?,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotion: Boolean,
    spokenUpdateText: String?,
    modifier: Modifier
) {
    val resources = LocalContext.current.resources
    val proximityLabel = when (peer?.proximity) {
        "Hot" -> stringResource(R.string.proximity_hot)
        "Cold" -> stringResource(R.string.proximity_cold)
        else -> stringResource(R.string.proximity_unknown)
    }
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
            val distanceLabel = peer.distance?.let {
                if (peer.distanceSource == com.fkurevija.pingring.data.RangingSourcePolicy.RangingSource.GNSS) {
                    stringResource(R.string.distance_with_uncertainty, it, peer.distanceAccuracyMeters ?: 0f)
                } else stringResource(R.string.distance_away, it)
            } ?: stringResource(R.string.proximity_signal, proximityLabel)
            Text(
                stringResource(R.string.tracked_peer_distance, peer.name, distanceLabel),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
        ProximityRing(
            color = ringColor,
            arrowDegrees = peer?.azimuth,
            distanceMeters = peer?.distance,
            beacon = beacon,
            accessibilityDescription = RadarAccessibilityPolicy.describeLocalized(
                resources = resources,
                peerName = peer?.name,
                distanceMeters = peer?.distance,
                azimuthDegrees = peer?.azimuth,
                proximityLabel = peer?.let { proximityLabel },
                beaconTitle = beacon?.title,
                distanceIsEstimate = peer?.distanceSource == com.fkurevija.pingring.data.RangingSourcePolicy.RangingSource.GNSS,
                distanceAccuracyMeters = peer?.distanceAccuracyMeters,
            ),
            accessibilityEnabled = radarScreenReaderSummaryEnabled,
            reduceMotion = reduceMotion,
            spokenUpdateText = spokenUpdateText,
            // Sized from the available width rather than fixed. The caller used
            // to pin this column to 260.dp while the ring inside it was also
            // 260.dp, so the name and distance heading above had
            // nowhere to go: the Column overflowed and the ring was clipped top
            // and bottom. Deriving the diameter from the width leaves room for
            // the surrounding text and still fits a narrow phone.
            modifier = Modifier.size(radarDiameter)
        )
        Spacer(Modifier.height(verticalBuffer))
        }
    }
}

@Composable
internal fun rememberBeaconPulse(reduceMotion: Boolean, active: Boolean): Pair<Float, Float> {
    if (reduceMotion || !active) return 0.9f to 0.35f
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
    return beaconPulseRadius to beaconPulseAlpha
}

@Composable
private fun ProximityRing(
    color: Color,
    arrowDegrees: Float?,
    distanceMeters: Float?,
    beacon: BeaconInfo?,
    accessibilityDescription: String,
    accessibilityEnabled: Boolean,
    reduceMotion: Boolean,
    spokenUpdateText: String?,
    modifier: Modifier
) {
    val (beaconPulseRadius, beaconPulseAlpha) = rememberBeaconPulse(reduceMotion, beacon != null)
    Box(modifier, contentAlignment = Alignment.Center) {
        spokenUpdateText?.let { announcement ->
            Box(Modifier.size(1.dp).semantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            })
        }
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
    onFocusPeer: (String?) -> Unit,
    modifier: Modifier
) {
    // Nearest first, everywhere in this sheet. The rows below and the tracking
    // dropdown above are two views of the same members, so they read from one
    // ordered list - a picker that offered them in a different order than the
    // list showed would make the list's ordering look arbitrary.
    val orderedPeers = remember(peers) { PeerListOrderPolicy.bySignalStrength(peers) }

    // One policy decides which notices appear and in what order, rather than two
    // inline conditions that could drift apart. The precedence it encodes - that
    // a dead Bluetooth radio hides the rest - is not obvious from reading the
    // markup, which is why it lives in a tested object.
    val advice = remember(connectivity) { LocalRadioAdvicePolicy.adviceFor(connectivity) }

    Column(modifier.padding(bottom = 8.dp)) {
        advice.forEach { notice ->
            LocalRadioAdviceCard(
                advice = notice,
                // The BLE-identity note is a capability statement, not a fault, so
                // it does not get the alarming error container.
                isError = notice != Advice.BLE_IDENTITY_UNAVAILABLE,
                onRecheck = { repository?.refreshConnectivityStatus() },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isFull = (peers.size + 1) >= MAX_PARTICIPANTS
            Text(
                if (isFull) stringResource(R.string.member_count_full, peers.size + 1, MAX_PARTICIPANTS)
                else stringResource(R.string.member_count, peers.size + 1, MAX_PARTICIPANTS),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        // The one member being tracked, as a control rather than only as a
    // highlight on a row. The per-row buttons below can already set this, but
    // they cannot show what is currently tracked without reading the list, and
    // they give no way back to the automatic choice once something has been
    // picked. Always available: choosing who to follow is the local user's own
    // decision, and the host no longer owns it.
    if (peers.isNotEmpty()) {
            TrackedMemberPicker(
                peers = orderedPeers,
                trackedPeerId = focusedPeerId,
                onSelect = onFocusPeer
            )
            Spacer(Modifier.height(8.dp))
        }
        // The list of members, or an empty-state message if nobody else has
        // joined. The empty state fills the remaining height so the sheet can
        // be "pulled up" even when empty, answering the host's gesture with a
        // real surface instead of a tiny sliver at the bottom.
        if (peers.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(orderedPeers, key = { it.peerId }) { peer ->
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
                                            stringResource(R.string.tracking_badge),
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
                                // Proximity as a word, not as a number. The dBm used
                                // to be shown here and it was the least useful thing
                                // on the row: it is a raw radio reading, two members
                                // in the same room routinely differ by 10 dB for
                                // reasons nobody can control, and reading it told a
                                // user nothing about where to walk. The row's dot
                                // already encodes the same thing visually, and the
                                // distance at the end encodes it precisely.
                                //
                                // The word stays even though the dot also encodes it,
                                // because the dot is colour: for a member who cannot
                                // separate green from blue, the dot is the one signal
                                // on this row they cannot read.
                                val proximityLabel = when (peer.proximity) {
                                    "Hot" -> stringResource(R.string.proximity_hot)
                                    "Cold" -> stringResource(R.string.proximity_cold)
                                    else -> stringResource(R.string.proximity_unknown)
                                }
                                Text(proximityLabel, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(peer.distance?.let { stringResource(R.string.distance_meters, it) } ?: stringResource(R.string.no_distance), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            // Tapping the row makes this the one member the
                            // radar draws and the UWB session ranges. Tapping
                            // the tracked member again returns to auto, so the
                            // choice is always reversible without leaving the
                            // screen.
                            IconButton(
                                onClick = { onFocusPeer(if (isFocused) null else peer.peerId) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isFocused) Icons.Default.MyLocation else Icons.Default.GpsFixed,
                                    contentDescription = stringResource(
                                        if (isFocused) R.string.stop_tracking_member else R.string.track_member,
                                        peer.name,
                                    ),
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
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_members_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
 * Every member is always selectable. This control used to go read-only on a
 * guest when the host had turned on "track host", which left the radar pointed
 * at somebody with nothing on screen to explain why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackedMemberPicker(
    peers: List<RadarPeerUpdate>,
    trackedPeerId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val trackedName = peers.firstOrNull { it.peerId == trackedPeerId }?.name
    val label = when {
        trackedName != null -> trackedName
        // No explicit choice: whatever FocusPolicy resolved is being tracked,
        // which is not necessarily in this list.
        else -> stringResource(R.string.automatic)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.tracking)) },
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
                text = { Text(stringResource(R.string.automatic)) },
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


/**
 * Everything about chat that the member can change, in one place.
 *
 * Opened from the radar between the beacon buttons and the transcript, so the
 * controls sit next to the chat they change rather than on a settings screen two
 * destinations away.
 *
 * Bubble colour only, and only from [ChatBubblePalette.presets]. There is no
 * custom picker and no "use the theme colour" option: a member picks a colour so
 * their messages can be found in a busy transcript, which stops working the
 * moment two phones render the same message differently.
 */
@Composable
internal fun ChatSettingsDialog(
    selectedColorArgb: Long?,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Who holds which colour, and which of those holders is us.
     *
     * Needed because a colour somebody else holds cannot be picked: two members
     * rendering the same colour is what makes a transcript unreadable, so the
     * swatch is shown as taken rather than silently failing on tap. The holder's
     * name is passed in rather than looked up so this stays a plain composable.
     */
    colourHolders: Map<Long, String> = emptyMap(),
    memberNameFor: (String) -> String = { it },
    localPeerId: String? = null,
    refusedColorArgb: Long? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.chat_bubble_colour_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refusedColorArgb != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chat_colour_refused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Wraps rather than scrolls sideways: twelve swatches in one
                // row needs more width than a phone has, and a horizontal
                // scroller hides the fact that there are eleven other choices.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatBubblePalette.presets.forEach { colorArgb ->
                        val selected = selectedColorArgb == colorArgb
                        // Ours is not taken, and ours is the one thing that must
                        // never grey out or a member cannot see or keep their own.
                        //
                        // A null localPeerId means we do not know who we are, and
                        // that disables nothing rather than everything: treating
                        // every holder as somebody else would grey out the
                        // member's own colour, which is the one case the whole
                        // screen has to get right. Unknown identity is a
                        // screenshot-test situation, not a real one.
                        val holder = colourHolders[colorArgb]
                        val heldByOther = holder != null &&
                            localPeerId != null &&
                            holder != localPeerId
                        val description = when {
                            selected -> stringResource(
                                R.string.set_chat_bubble_colour_to,
                                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                            )
                            heldByOther -> stringResource(
                                R.string.chat_colour_taken_by,
                                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                                memberNameFor(holder ?: ""),
                            )
                            else -> stringResource(
                                R.string.set_chat_bubble_colour_to,
                                String.format(java.util.Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = description
                                    // Announced as well as drawn, and only when it is
                                    // genuinely taken: dimming is a colour change
                                    // alone, which a screen reader cannot convey. Set
                                    // unconditionally it would announce all twelve
                                    // swatches as disabled.
                                    if (heldByOther) disabled()
                                }
                                .selectable(
                                    selected = selected,
                                    enabled = !heldByOther,
                                    role = Role.RadioButton,
                                    onClick = { onColorSelected(colorArgb) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    // Dimmed rather than hidden: the member can see
                                    // the colour is spoken for and who has it,
                                    // instead of finding eleven options and no
                                    // explanation for the gap.
                                    .background(
                                        Color(colorArgb).copy(
                                            alpha = if (heldByOther) 0.25f else 1f,
                                        ),
                                        CircleShape,
                                    )
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconCustomizationDialog(
    localBeacon: BeaconInfo?,
    onDismiss: () -> Unit,
    onActivate: (title: String, colorArgb: Long, patternName: String) -> Unit,
    onStop: () -> Unit,
    onTestVibrate: (LongArray) -> Unit
) {
    var isVibrationMenuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // The gap only means anything when something is repeating, so it is hidden
    // otherwise rather than shown greyed out - a control that does nothing yet
    // still looks like a setting.
    val gapOptions = remember { listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L) }
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(BeaconPreset.entries.first()) }
    val initialPresetTitle = stringResource(selectedPreset.defaultTitleRes)
    var title by remember { mutableStateOf(localBeacon?.title ?: initialPresetTitle) }
    var selectedColor by remember { mutableLongStateOf(localBeacon?.colorArgb ?: selectedPreset.defaultColorArgb) }
    var selectedPattern by remember {
        mutableStateOf(
            localBeacon?.let { BeaconVibrationPattern.fromName(it.vibrationPattern) }
                ?: selectedPreset.defaultPattern
        )
    }

    val colorPalette = listOf(
        0xFFFF1744, // Red
        0xFFFF9100, // Amber
        0xFF00E676, // Green
        0xFF00E5FF, // Cyan
        0xFFD500F9  // Purple
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (localBeacon != null) R.string.update_active_beacon else R.string.create_beacon),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Presets & Intent
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.select_preset),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
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
                                            title = context.getString(preset.defaultTitleRes)
                                            selectedColor = preset.defaultColorArgb
                                            selectedPattern = preset.defaultPattern
                                            isVibrationMenuExpanded = false
                                        },
                                        label = { Text(stringResource(preset.labelRes, preset.iconEmoji)) }
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(20.dp)
                                    .height(36.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    )
                            )
                        }

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = TextInputPolicy.sanitizeBeaconTitle(it) },
                            label = { Text(stringResource(R.string.beacon_message_intent)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            )
                        )
                    }
                }

                // Section 2: Color Palette
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.beacon_color),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colorPalette.forEach { colorArgb ->
                                val isSelected = selectedColor == colorArgb
                                val description = stringResource(
                                    R.string.set_beacon_color_to,
                                    String.format(Locale.ROOT, "#%06X", colorArgb and 0xFFFFFFL),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .semantics { contentDescription = description }
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.RadioButton,
                                            onClick = { selectedColor = colorArgb },
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .background(Color(colorArgb), CircleShape)
                                            .border(
                                                width = if (isSelected) 3.dp else 0.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                shape = CircleShape,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 3: Vibration & Testing
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.vibration_rhythm_intensity),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ExposedDropdownMenuBox(
                            expanded = isVibrationMenuExpanded,
                            onExpandedChange = { isVibrationMenuExpanded = !isVibrationMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = stringResource(selectedPattern.labelRes),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVibrationMenuExpanded) },
                                shape = RoundedCornerShape(12.dp),
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
                                        text = { Text(stringResource(pattern.labelRes)) },
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
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.test_vibration))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onActivate(
                        title.ifBlank { context.getString(R.string.active_beacon_title) },
                        selectedColor,
                        selectedPattern.name,
                    )
                    onDismiss()
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(if (localBeacon != null) R.string.update_beacon else R.string.activate_beacon), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (localBeacon != null) {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.stop_beacon), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

private val BeaconVibrationPattern.labelRes: Int
    get() = when (this) {
        BeaconVibrationPattern.SOS_URGENT -> R.string.vibration_sos_urgent
        BeaconVibrationPattern.RAPID_ALERT -> R.string.vibration_rapid_alert
        BeaconVibrationPattern.STEADY_PULSE -> R.string.vibration_steady_pulse
        BeaconVibrationPattern.GENTLE_PULSE -> R.string.vibration_gentle_pulse
        BeaconVibrationPattern.SILENT -> R.string.vibration_none
    }

private val BeaconPreset.labelRes: Int
    get() = when (this) {
        BeaconPreset.GONDOR_CALLS_FOR_AID -> R.string.preset_gondor_calls_for_aid
        BeaconPreset.FOUND -> R.string.preset_found
        BeaconPreset.REGROUP -> R.string.preset_regroup
        BeaconPreset.CAUTION -> R.string.preset_caution
        BeaconPreset.SOS -> R.string.preset_emergency
        BeaconPreset.CUSTOM -> R.string.preset_custom
    }

private val BeaconPreset.defaultTitleRes: Int
    get() = when (this) {
        BeaconPreset.GONDOR_CALLS_FOR_AID -> R.string.preset_gondor_calls_for_aid_title
        BeaconPreset.FOUND -> R.string.preset_found_title
        BeaconPreset.REGROUP -> R.string.preset_regroup_title
        BeaconPreset.CAUTION -> R.string.preset_caution_title
        BeaconPreset.SOS -> R.string.preset_emergency_title
        BeaconPreset.CUSTOM -> R.string.preset_custom_title
    }

private fun Color.Companion.lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
