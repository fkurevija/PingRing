package com.fkurevija.pingring.ui.screens

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object EventCodeParser {
    private val allowedEventCodePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
    private val queryKeys = listOf("event", "eventCode", "code")

    fun parse(rawValue: String?): String? {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val candidates = mutableListOf(raw)
        runCatching {
            val uri = URI(raw)
            candidates += extractFromQuery(uri.rawQuery)
            val pathSegment = uri.path?.substringAfterLast('/')?.trim().orEmpty()
            if (pathSegment.isNotEmpty() && pathSegment.lowercase() != "join") {
                candidates += pathSegment
            }
        }

        return candidates
            .map { it.trim() }
            .firstOrNull { allowedEventCodePattern.matches(it) }
    }

    private fun extractFromQuery(rawQuery: String?): List<String> {
        if (rawQuery.isNullOrBlank()) return emptyList()
        return rawQuery
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                if (key !in queryKeys) return@mapNotNull null
                val value = part.substringAfter('=', missingDelimiterValue = "").trim()
                if (value.isBlank()) return@mapNotNull null
                URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
    }
}
