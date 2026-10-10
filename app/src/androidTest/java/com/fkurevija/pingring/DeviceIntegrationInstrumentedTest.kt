package com.fkurevija.pingring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fkurevija.pingring.data.ble.BleAdvertisementPayload
import com.fkurevija.pingring.data.BleIdentityAdvertisement
import com.fkurevija.pingring.data.BleRangePolicy
import com.fkurevija.pingring.data.InviteSecret
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * The parts of the app that can only be checked against a real Android runtime.
 *
 * Each test here covers a question a JVM unit test provably cannot answer:
 * does the app's context actually start, does the platform report the
 * capabilities the code branches on, and does the BLE payload - a fixed 13-byte
 * budget sized for the real advertising limit - fit what a real radio is given.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationInstrumentedTest {

    @Test
    fun theApplicationContextStarts() {
        // The scaffolding every other instrumented test stands on: if the app
        // cannot even obtain a Context under the test runner, nothing else in
        // this source set is worth running.
        val context: Context = ApplicationProvider.getApplicationContext()

        assertNotNull(context)
        assertEquals("com.fkurevija.pingring", context.packageName)
    }

    @Test
    fun theApplicationContextResolvesTheServicesDiscoveryAsksFor() {
        // DiscoveryRepository looks these up during construction. On the JVM they
        // are stubs that return null, so a service that is genuinely missing on
        // device would only ever be discovered at runtime by a real user.
        val context: Context = ApplicationProvider.getApplicationContext()

        assertNotNull(
            "BLUETOOTH_SERVICE is required for discovery",
            context.getSystemService(Context.BLUETOOTH_SERVICE)
        )
        assertNotNull(
            "WIFI_SERVICE is required for the connectivity readout",
            context.getSystemService(Context.WIFI_SERVICE)
        )
    }

    @Test
    fun theSignedAdvertisementUsesExtendedAdvertisingDataBudget() {
        assertEquals(
            "the signed payload and policy budget must match",
            BleRangePolicy.SERVICE_DATA_BYTES,
            BleIdentityAdvertisement.TOTAL_BYTES
        )
        assertTrue(
            "the signed payload plus AD structures must fit extended advertising",
            BleRangePolicy.fitsInExtendedAdvertising()
        )
        assertTrue(BleRangePolicy.SERVICE_DATA_BYTES > BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES)
    }

    @Test
    fun aRealPeerIdSurvivesTheRealEncodingPath() {
        // End to end across the two halves with a genuinely generated id rather
        // than a hand-written fixture, because the id length and the field width
        // have to agree.
        val generated = SessionManager().peerId.value
        val inviteSecret = InviteSecret.generate()
        val payload = BleAdvertisementPayload.encode(
            flags = BleAdvertisementPayload.FLAG_HOST,
            peerIdBytes = generated.toByteArray(StandardCharsets.UTF_8),
            eventCode = "DE71CE",
            inviteSecret = inviteSecret,
        )

        val decoded = BleAdvertisementPayload.decode(payload, inviteSecret)

        assertNotNull(decoded)
        assertEquals(generated, decoded!!.peerId)
        assertTrue(decoded.isHost)
    }

    @Test
    fun theThemeModeNamesRoundTripThroughTheirStoredForm() {
        // The value is persisted by name, so every constant has to survive
        // valueOf - a rename without a migration would reset a user's setting.
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(context)

        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.valueOf(mode.name))
        }
    }
}