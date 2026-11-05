package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.chat.ChatBubbleColorPolicy
import com.combustiblelemonlabs.pingring.data.chat.ChatTimelinePolicy
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.radar.RadarPeerUpdate
import com.combustiblelemonlabs.pingring.data.session.SessionState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RadarCompactChatPane(
    maxHeight: Dp,
    sessionState: SessionState,
    peers: List<RadarPeerUpdate>,
    focusedPeer: RadarPeerUpdate?,
    focusedBeacon: BeaconInfo?,
    activeBeacons: Map<String, BeaconInfo>,
    endedBeacons: List<BeaconInfo>,
    localBeacon: BeaconInfo?,
    discoveryRepository: DiscoveryRepository?,
    localPeerId: String?,
    senderName: String,
    chatBubbleColorArgb: Long?,
    readReceiptsVisible: Boolean,
    silencedBeaconIds: Set<String>,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotionEnabled: Boolean,
    spokenUpdateText: String?,
    trackedDirectionAlpha: Float,
    showLastKnownDirection: Boolean,
    onShowChatSettings: () -> Unit,
    onShowBeaconSheet: () -> Unit,
) {
    val isImeVisible = WindowInsets.isImeVisible
    val chatEntries = remember(sessionState.chatMessages, activeBeacons, endedBeacons) {
        ChatTimelinePolicy.build(
            messages = sessionState.chatMessages,
            activeBeacons = activeBeacons.values.toList(),
            endedBeacons = endedBeacons,
        ).reversed()
    }
    val chatListState = rememberLazyListState()
    val latestSenderColors = remember(sessionState.chatMessages) {
        ChatBubbleColorPolicy.latestColorsBySender(sessionState.chatMessages)
    }
    var followingNewest by remember { mutableStateOf(true) }
    LaunchedEffect(chatListState) {
        var dragging = false
        while (true) {
            withFrameNanos { }
            if (chatListState.isScrollInProgress) {
                dragging = true
            } else if (dragging) {
                followingNewest = chatListState.firstVisibleItemIndex == 0
                dragging = false
            }
        }
    }
    LaunchedEffect(chatEntries.firstOrNull()?.key) {
        if (followingNewest) chatListState.scrollToItem(0)
    }

    val density = LocalDensity.current
    var topContentHeightPx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().onSizeChanged { topContentHeightPx = it.height }) {
            RadarContent(
                focusedPeer,
                peers.isNotEmpty(),
                focusedBeacon,
                radarScreenReaderSummaryEnabled,
                reduceMotionEnabled,
                spokenUpdateText,
                Modifier.fillMaxWidth(),
                trackedDirectionAlpha,
                showLastKnownDirection,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onShowChatSettings, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_settings), textAlign = TextAlign.Center)
                }
                BeaconActionButton(
                    localBeacon = localBeacon,
                    onOpen = onShowBeaconSheet,
                    onStop = { discoveryRepository?.cancelLocalBeacon() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val topContentHeightDp = with(density) { topContentHeightPx.toDp() }
        val chatCardOffsetY = if (isImeVisible) 0.dp else topContentHeightDp
        val chatCardHeight = if (isImeVisible) maxHeight else (maxHeight - topContentHeightDp).coerceAtLeast(0.dp)
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
            readReceiptsVisible = readReceiptsVisible,
            latestSenderColors = latestSenderColors,
            silencedBeaconIds = silencedBeaconIds,
            onToggleBeaconSilenced = { id, silenced -> discoveryRepository?.setBeaconSilenced(id, silenced) },
            onVisibleMessagesChanged = { ids -> discoveryRepository?.acknowledgeVisibleChatMessages(ids) },
            onSendMessage = { message ->
                discoveryRepository?.broadcastOutgoingChatMessage(message, senderName, chatBubbleColorArgb)
            },
        )
    }
}
