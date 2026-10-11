package com.fkurevija.pingring.ui.screens

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.fkurevija.pingring.data.session.InviteSecret
import com.fkurevija.pingring.data.session.Event

data class PingRingInvite(val eventCode: String, val secret: String?) {
    fun toQrValue(): String = "pingring://join?event=$eventCode" +
        (secret?.let { "&secret=$it" } ?: "")
}

object PingRingCodeParser {
    private val allowedEventCodePattern = Regex("^[A-Fa-f0-9]{6}$")
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

    fun parseInvite(rawValue: String?): PingRingInvite? {
        val eventCode = parse(rawValue) ?: return null
        val raw = rawValue?.trim().orEmpty()
        val rawQuery = runCatching { URI(raw).rawQuery }.getOrNull()
        val secretValues = rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "").trim()
            if (key != "secret") return@mapNotNull null
            URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        if (secretValues.size > 1) return null
        val secret = secretValues.singleOrNull()
        if (secret != null && InviteSecret.decode(secret) == null) return null
        return PingRingInvite(eventCode, secret)
    }
}
