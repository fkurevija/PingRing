package com.combustiblelemonlabs.pingring

import com.combustiblelemonlabs.pingring.data.DiagnosticsFacts
import com.combustiblelemonlabs.pingring.data.DiagnosticsPolicy
import com.combustiblelemonlabs.pingring.data.DiagnosticsPolicy.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.combustiblelemonlabs.pingring.data.session.Event

/**
 * What the About screen reports, and in what order.
 *
 * The failure that matters most is not a wrong format - it is a diagnostic
 * report that carries something about a person, because these get pasted into
 * public issue trackers.
 */
class DiagnosticsPolicyTest {

    private fun facts(
        versionName: String = "1.0",
        versionCode: Int = 1,
        isDebugBuild: Boolean = false,
        uwb: Boolean = true,
        signedBle: Boolean = true,
        bluetooth: Boolean = true,
        wifi: Boolean = true,
        vibrator: Boolean = true,
    ) = DiagnosticsFacts(
        versionName = versionName,
        versionCode = versionCode,
        isDebugBuild = isDebugBuild,
        applicationId = "com.combustiblelemonlabs.pingring",
        deviceManufacturer = "Google",
        deviceModel = "Pixel 10",
        androidRelease = "17",
        apiLevel = 37,
        buildId = "BUILD.1",
        uwbSupported = uwb,
        signedBleIdentitySupported = signedBle,
        bluetoothEnabled = bluetooth,
        wifiEnabled = wifi,
        hasVibrator = vibrator,
    )

    /** Stand-in labels, so the test does not need a Context or resources. */
    private fun label(key: Key) = key.name

    @Test
    fun `the version a user reports is the one that will be read back`() {
        val rows = DiagnosticsPolicy.rows(facts(versionName = "1.4", versionCode = 7))

        // Name and code together: "1.4" alone cannot distinguish a released 1.4
        // from a locally-built one, which is the exact ambiguity a bug report has
        // to avoid.
        assertEquals("1.4 (7)", rows.first { it.key == Key.APP_VERSION }.value)
    }

    @Test
    fun `a release build says so and a debug build says so`() {
        assertEquals(
            "Release",
            DiagnosticsPolicy.rows(facts(isDebugBuild = false))
                .first { it.key == Key.BUILD_TYPE }.value,
        )
        assertEquals(
            "Debug",
            DiagnosticsPolicy.rows(facts(isDebugBuild = true))
                .first { it.key == Key.BUILD_TYPE }.value,
        )
    }

    @Test
    fun `capabilities are words rather than booleans`() {
        // "true" in a pasted report reads like a value nobody set deliberately.
        val rows = DiagnosticsPolicy.rows(facts(uwb = false, wifi = false))

        assertEquals("No", rows.first { it.key == Key.UWB_SUPPORTED }.value)
        assertEquals("No", rows.first { it.key == Key.WIFI_ENABLED }.value)
        assertEquals("Yes", rows.first { it.key == Key.BLUETOOTH_ENABLED }.value)
    }

    @Test
    fun `every fact is reported even when it is the boring answer`() {
        // A row that is missing from a pasted report is indistinguishable from a
        // feature that does not exist. Everything is always present.
        val keys = DiagnosticsPolicy.rows(facts()).map { it.key }

        assertEquals(Key.entries.toSet(), keys.toSet())
    }

    @Test
    fun `the report carries no value other than the facts it was given`() {
        // The privacy invariant, asserted rather than asserted-in-a-comment.
        //
        // There is no field on DiagnosticsFacts that a name, an event code, a
        // coordinate or a peer id could occupy, so the only strings a report can
        // contain are the ones the caller passed in. This test pins that: every
        // line's value must be traceable to a field, and no extra text may appear.
        val given = facts()
        val report = DiagnosticsPolicy.report(given, ::label)
        val allowed = DiagnosticsPolicy.rows(given).map { it.value }.toSet()

        report.lines().forEach { line ->
            val value = line.substringAfter(": ")
            assertTrue(
                "report line carries a value that is not a fact: $line",
                value in allowed,
            )
        }
        assertFalse(
            "the report must not contain a placeholder for anything absent",
            report.contains("null") || report.contains("undefined"),
        )
    }

    @Test
    fun `the report is one key and value per line with no preamble`() {
        // So it pastes into an issue template without dragging formatting along.
        val report = DiagnosticsPolicy.report(facts(), ::label)
        val lines = report.lines()

        assertEquals(DiagnosticsPolicy.rows(facts()).size, lines.size)
        lines.forEach { line ->
            assertTrue("line is not 'key: value': $line", line.contains(": "))
        }
        assertTrue(lines.none { it.isBlank() })
    }

    @Test
    fun `a manufacturer with no model does not leave a dangling space`() {
        val odd = facts().copy(deviceManufacturer = "Unknown", deviceModel = "")

        assertEquals(
            "Unknown",
            DiagnosticsPolicy.rows(odd).first { it.key == Key.DEVICE }.value,
        )
    }
}