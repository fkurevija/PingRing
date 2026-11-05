package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.DataExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExportManagerTest {

    @Test
    fun generateExportPayload() {
        val payload = DataExportManager.generateExport(
            peerCount = 4,
            recentDistances = listOf(2.0f, 4.0f, 3.0f),
            auditEvents = listOf("Peer A connected", "Peer B disconnected"),
        )
        assertEquals(4, payload.anonymizedPeerCount)
        assertTrue(payload.heatmapSummary.contains("3 samples"))
        assertEquals(2, payload.connectionAuditLogs.size)
    }
}
