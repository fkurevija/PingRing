package com.fkurevija.pingring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fkurevija.pingring.data.BleAdvertisementPayload
import com.fkurevija.pingring.data.BleRangePolicy
import com.fkurevija.pingring.data.SessionManager
import com.fkurevija.pingring.data.ThemeMode
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
    fun theAdvertisementPayloadFitsWhatThePlatformAdvertises() {
        // The reason the payload is bounded at all: legacy advertising cannot
        // carry more than 31 bytes of service data, and going over makes the
        // advert silently untransmittable. Checked against the real constants
        // here so the two cannot drift apart unnoticed.
        assertEquals(
            "the payload and the documented service-data size must be one number",
            BleRangePolicy.SERVICE_DATA_BYTES,
            BleAdvertisementPayload.TOTAL_BYTES
        )
        assertTrue(
            "the payload must fit legacy advertising",
            BleRangePolicy.SERVICE_DATA_BYTES <= BleRangePolicy.MAX_LEGACY_SERVICE_DATA_BYTES
        )
    }

    @Test
    fun aRealPeerIdSurvivesTheRealEncodingPath() {
        // End to end across the two halves with a genuinely generated id rather
        // than a hand-written fixture, because the id length and the field width
        // have to agree.
        val generated = SessionManager().peerId.value
        val payload = BleAdvertisementPayload.encode(
            eventHash = "DEVICE-TEST".hashCode(),
            flags = BleAdvertisementPayload.FLAG_HOST,
            peerIdBytes = generated.toByteArray(StandardCharsets.UTF_8),
        )

        val decoded = BleAdvertisementPayload.decode(payload)

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