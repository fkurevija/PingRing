package com.combustiblelemonlabs.pingring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.combustiblelemonlabs.pingring.data.DiscoveryRepository
import com.combustiblelemonlabs.pingring.data.session.Event
import com.combustiblelemonlabs.pingring.data.LanDebugConnectionsManager
import com.combustiblelemonlabs.pingring.data.NearbyTransport
import com.combustiblelemonlabs.pingring.data.session.MeshPresencePayloadDto
import com.combustiblelemonlabs.pingring.data.session.SessionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.SessionState

class TrackingSelectionInstrumentedTest {
    private class TestTransport : NearbyTransport by LanDebugConnectionsManager("127.0.0.1") {
        override val connectionEstablished = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val peerDisconnected = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val receivedMeshPresence = MutableSharedFlow<MeshPresencePayloadDto>(extraBufferCapacity = 8)
        val sentPresence = mutableListOf<MeshPresencePayloadDto>()

        override fun broadcastMemberPresence(presence: MeshPresencePayloadDto) {
            sentPresence += presence
        }

        override fun startAdvertising(
            eventId: String,
            localPeerId: String,
            useWifiDirect: Boolean,
            useHotspot: Boolean,
        ) = Unit

        override fun startDiscovery(eventId: String, localPeerId: String) = Unit
    }

    @Test
    fun usernameEditsUpdateLocalStateAndRemoteMemberNamesWithoutChangingFocus() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager()
        session.hostEvent(Event.create(id = "NAMES1", hostPeerId = session.peerId.value, useUwb = false))
        session.setUserName("Ana")
        val transport = TestTransport()
        val repository = DiscoveryRepository(context, session, transport)
        try {
            repository.startDiscovery()
            withTimeout(5_000L) { transport.receivedMeshPresence.subscriptionCount.first { it > 0 } }
            transport.receivedMeshPresence.emit(MeshPresencePayloadDto("NAMES1", "peer", "Bo"))
            withTimeout(5_000L) { repository.peerUpdates.first { it.any { peer -> peer.name == "Bo" } } }
            withTimeout(5_000L) { repository.focusedPeerId.first { it == "peer" } }

            repository.updateLocalUsername("  A\u200Blex  ")
            assertEquals("Alex", session.sessionState.value.userName)
            assertEquals(
                MeshPresencePayloadDto("NAMES1", session.peerId.value, "Alex"),
                transport.sentPresence.last(),
            )
            transport.receivedMeshPresence.emit(MeshPresencePayloadDto("NAMES1", "peer", "Bea"))
            withTimeout(5_000L) { repository.peerUpdates.first { it.any { peer -> peer.name == "Bea" } } }
            assertEquals("peer", repository.focusedPeerId.value)
            assertEquals("Bea", session.sessionState.value.friends.single().name)
        } finally {
            repository.stopDiscovery()
        }
    }

    @Test
    fun hostAutomaticallySelectsAndCannotClearTrackingWhileMembersRemain() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager()
        session.hostEvent(Event.create(id = "FOCUS1", hostPeerId = session.peerId.value, useUwb = false))
        val transport = TestTransport()
        val repository = DiscoveryRepository(context, session, transport)
        try {
            repository.startDiscovery()
            withTimeout(5_000L) { transport.connectionEstablished.subscriptionCount.first { it > 0 } }
            transport.connectionEstablished.emit("first")
            withTimeout(5_000L) { repository.focusedPeerId.first { it == "first" } }
            transport.connectionEstablished.emit("second")
            withTimeout(5_000L) { repository.peerUpdates.first { it.size == 2 } }
            assertEquals("first", repository.focusedPeerId.value)

            repository.setFocusedPeer("second")
            assertEquals("second", repository.focusedPeerId.value)
            repository.setFocusedPeer("second")
            repository.setFocusedPeer(session.peerId.value)
            repository.setFocusedPeer("unavailable")
            assertEquals("second", repository.focusedPeerId.value)

            transport.peerDisconnected.emit("second")
            withTimeout(5_000L) { repository.focusedPeerId.first { it == "first" } }
            transport.peerDisconnected.emit("first")
            withTimeout(5_000L) { repository.focusedPeerId.first { it == null } }
            transport.connectionEstablished.emit("new-member")
            withTimeout(5_000L) { repository.focusedPeerId.first { it == "new-member" } }
        } finally {
            repository.stopDiscovery()
        }
    }

    @Test
    fun guestSelectsHostOnConnectionBeforeAnyRangingMeasurement() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager()
        session.joinEvent(Event.create(id = "FOCUS2", hostPeerId = "host", useUwb = false))
        val transport = TestTransport()
        val repository = DiscoveryRepository(context, session, transport)
        try {
            repository.startDiscovery()
            withTimeout(5_000L) { transport.connectionEstablished.subscriptionCount.first { it > 0 } }
            transport.connectionEstablished.emit("host")
            withTimeout(5_000L) { repository.focusedPeerId.first { it == "host" } }
            assertEquals("host", repository.resolveFocusedPeer())
        } finally {
            repository.stopDiscovery()
        }
    }
}
