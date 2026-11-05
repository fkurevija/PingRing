package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.session.BeaconInfo
import com.combustiblelemonlabs.pingring.data.chat.BeaconPayloadDto
import com.combustiblelemonlabs.pingring.data.chat.BeaconPreset
import com.combustiblelemonlabs.pingring.data.chat.BeaconVibrationPattern
import com.combustiblelemonlabs.pingring.data.session.BleIdentityKeyPayloadDto
import com.combustiblelemonlabs.pingring.data.chat.ChatHistoryPayloadDto
import com.combustiblelemonlabs.pingring.data.session.ChatMessage
import com.combustiblelemonlabs.pingring.data.chat.ChatPayloadDto
import com.combustiblelemonlabs.pingring.data.chat.ChatTimelineItem
import com.combustiblelemonlabs.pingring.data.session.EventControlPayloadDto
import com.combustiblelemonlabs.pingring.data.session.InviteSecretPayloadDto
import com.combustiblelemonlabs.pingring.data.session.JoinProofPayloadDto
import com.combustiblelemonlabs.pingring.data.session.MemberDeparturePayloadDto
import com.combustiblelemonlabs.pingring.data.session.MeshLinkAuthenticationPayloadDto
import com.combustiblelemonlabs.pingring.data.session.MeshPresencePayloadDto
import com.combustiblelemonlabs.pingring.data.ranging.UwbPeerAddressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.radar.PeerRangingPayloadDto

class DataModelsUnitTest {

    @Test
    fun chatTimelineItemMsgKeyAndTimestampFormat() {
        val msg = ChatMessage(
            id = "msg1",
            senderPeerId = "p1",
            senderName = "Alex",
            messageText = "Hello",
            timestamp = 1000L,
            colorArgb = 0xFF123456,
        )
        val item = ChatTimelineItem.Msg(msg)
        assertEquals(1000L, item.timestamp)
        assertEquals("m:msg1", item.key)
        assertEquals(msg, item.message)
    }

    @Test
    fun chatTimelineItemBeaconKeyAndTimestampFormat() {
        val beacon = BeaconInfo(
            id = "b1",
            senderPeerId = "p1",
            senderName = "Alex",
            title = "Help",
            colorArgb = 0xFFFF0000,
            vibrationPattern = "RAPID_ALERT",
            isActive = true,
            timestamp = 2000L,
        )
        val item = ChatTimelineItem.Beacon(beacon)
        assertEquals(2000L, item.timestamp)
        assertEquals("b:b1", item.key)
        assertEquals(beacon, item.beacon)
    }

    @Test
    fun beaconVibrationPatternFromNameReturnsExpected() {
        assertEquals(BeaconVibrationPattern.SOS_URGENT, BeaconVibrationPattern.fromName("SOS_URGENT"))
        assertEquals(BeaconVibrationPattern.RAPID_ALERT, BeaconVibrationPattern.fromName("RAPID_ALERT"))
        assertEquals(BeaconVibrationPattern.STEADY_PULSE, BeaconVibrationPattern.fromName("STEADY_PULSE"))
        assertEquals(BeaconVibrationPattern.GENTLE_PULSE, BeaconVibrationPattern.fromName("GENTLE_PULSE"))
        assertEquals(BeaconVibrationPattern.SILENT, BeaconVibrationPattern.fromName("SILENT"))
        assertEquals(BeaconVibrationPattern.STEADY_PULSE, BeaconVibrationPattern.fromName("NON_EXISTENT_PATTERN"))
    }

    @Test
    fun beaconPresetFieldsAreValid() {
        BeaconPreset.entries.forEach { preset ->
            assertTrue(preset.label.isNotBlank())
            assertTrue(preset.iconEmoji.isNotBlank())
            assertTrue(preset.defaultTitle.isNotBlank())
            assertNotNull(preset.defaultPattern)
        }
    }

    @Test
    fun verifyPayloadDtosInstantiationAndFields() {
        val chatDto = ChatPayloadDto("c1", "p1", "Alex", "Hi", 100L, 0xFF0000)
        assertEquals("c1", chatDto.id)
        assertEquals("p1", chatDto.senderPeerId)
        assertEquals("Alex", chatDto.senderName)

        val historyDto = ChatHistoryPayloadDto(listOf(chatDto))
        assertEquals(1, historyDto.messages.size)

        val beaconDto = BeaconPayloadDto("b1", "p1", "Alex", "Alert", 0xFF0000, "RAPID_ALERT", true, 100L)
        assertEquals("b1", beaconDto.id)

        val ctrlDto = EventControlPayloadDto("code1", "local-id", false, true, true, false, false, false)
        assertEquals("code1", ctrlDto.eventCode)

        val depDto = MemberDeparturePayloadDto("code1", "p1")
        assertEquals("p1", depDto.peerId)

        val inviteDto = InviteSecretPayloadDto("code1", "secret", "p1")
        assertEquals("secret", inviteDto.inviteSecret)

        val proofDto = JoinProofPayloadDto("code1", "secret", "Alex")
        assertEquals("secret", proofDto.inviteSecret)

        val presenceDto = MeshPresencePayloadDto("code1", "p1", "Alex")
        assertEquals("p1", presenceDto.peerId)

        val linkAuthDto = MeshLinkAuthenticationPayloadDto("code1", "p1", "p2", "challenge")
        assertEquals("challenge", linkAuthDto.challenge)

        val bleKeyDto = BleIdentityKeyPayloadDto("code1", "p1", "pubKey")
        assertEquals("pubKey", bleKeyDto.publicKey)

        val uwbAddrDto = UwbPeerAddressDto("p1", "address")
        assertEquals("address", uwbAddrDto.address)

        val rangingDto = PeerRangingPayloadDto("code1", "p1", 1L, "mac", null)
        assertEquals("code1", rangingDto.eventCode)
    }
}
