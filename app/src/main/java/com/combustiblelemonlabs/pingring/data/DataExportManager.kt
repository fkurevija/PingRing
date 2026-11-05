package com.combustiblelemonlabs.pingring.data

import java.util.Locale

/**
 * Handles local export and API generation for aggregated, anonymized movement heatmaps
 * and connection auditing.
 */
object DataExportManager {

    data class ExportPayload(
        val timestampMillis: Long,
        val anonymizedPeerCount: Int,
        val heatmapSummary: String,
        val connectionAuditLogs: List<String>,
    )

    fun generateExport(
        peerCount: Int,
        recentDistances: List<Float>,
        auditEvents: List<String>,
    ): ExportPayload {
        val heatmap = "Heatmap: ${recentDistanceSummary(recentDistances)}"
        return ExportPayload(
            timestampMillis = System.currentTimeMillis(),
            anonymizedPeerCount = peerCount,
            heatmapSummary = heatmap,
            connectionAuditLogs = auditEvents.takeLast(50),
        )
    }

    private fun recentDistanceSummary(distances: List<Float>): String {
        if (distances.isEmpty()) return "No movement data"
        val avg = distances.average()
        return "Avg distance: %.2fm across %d samples".format(Locale.ROOT, avg, distances.size)
    }
}
