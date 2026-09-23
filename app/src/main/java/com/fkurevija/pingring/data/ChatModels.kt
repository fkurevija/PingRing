package com.fkurevija.pingring.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatPayloadDto(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class NudgePayloadDto(
    val id: String,
    val senderPeerId: String,
    val senderName: String,
    val targetPeerId: String?, // null = broadcast to all
    val timestamp: Long
)
