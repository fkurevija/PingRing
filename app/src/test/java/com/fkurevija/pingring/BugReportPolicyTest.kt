package com.fkurevija.pingring

import com.fkurevija.pingring.data.BugReportPolicy
import com.fkurevija.pingring.data.DiagnosticsFacts
import com.fkurevija.pingring.data.DiagnosticsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The link that carries a bug report to the tracker.
 *
 * The whole feature is "the diagnostics arrive already typed into the issue",
 * so every test here is really the same question asked of a different
 * character: does this byte survive the trip through a query string, or does it
 * quietly end the parameter?
 */
class BugReportPolicyTest {

    private val facts = DiagnosticsFacts(
        versionName = "1.4",
        versionCode = 7,
        isDebugBuild = false,
        applicationId = "com.fkurevija.pingring",
        deviceManufacturer = "Google",
        deviceModel = "Pixel 10",
        androidRelease = "17",
        apiLevel = 37,
        buildId = "BUILD.TEST",
        uwbSupported = true,
        signedBleIdentitySupported = false,
        bluetoothEnabled = true,
        wifiEnabled = false,
        hasVibrator = true,
    )

    private fun report() = DiagnosticsPolicy.report(facts) { it.name.lowercase() }

    /** The body parameter, decoded back out - i.e. what the tracker will show. */
    private fun bodyOf(url: String): String = URLDecoder.decode(url.substringAfter("body=", ""), "UTF-8")

    private fun titleOf(url: String): String =
        URLDecoder.decode(url.substringAfter("title=").substringBefore("&body="), "UTF-8")

    @Test
    fun `a report reaches the tracker with its line breaks intact`() {
        // The line breaks are the whole structure of the report. If they encoded
        // as a literal "+" or collapsed, eleven rows would arrive as one line.
        val url = BugReportPolicy.issueUrl("PingRing 1.4 on Google Pixel 10", report())

        assertEquals(report(), bodyOf(url))
    }

    @Test
    fun `the eleven diagnostic lines all survive`() {
        val body = bodyOf(BugReportPolicy.issueUrl("t", report()))

        assertEquals(11, body.lines().size)
        assertTrue(body.contains("BUILD.TEST"))
        assertFalse(body.contains("+"))
    }

    @Test
    fun `an ampersand in a build id cannot end the parameter early`() {
        // The failure this whole file exists to prevent: a raw "&" splits the
        // query string, the tracker's server sees a parameter it does not know,
        // and the report silently loses its last line.
        val body = DiagnosticsPolicy.report(facts.copy(buildId = "abc&def")) { it.name.lowercase() }
        val url = BugReportPolicy.issueUrl("t", body)

        assertTrue(bodyOf(url).contains("abc&def"))
        assertEquals(body, bodyOf(url))
    }
    @Test
    fun `every query-breaking character round-trips instead of ending the value`() {
        // A build fingerprint or device name can contain any of these. Each one
        // terminates the parameter or the value in some browser or client, and a
        // report cut in half is worse than one that did not arrive.
        val nasty = "#?&=/+%\"'\\ \n\ttab"
        val url = BugReportPolicy.issueUrl("t", nasty)

        assertEquals(nasty, bodyOf(url))
    }

    @Test
    fun `a space is escaped rather than becoming a plus`() {
        // "+" is legal in a query string and means a space to a form parser, but
        // some editors take it literally. "%20" is unambiguous everywhere.
        val url = BugReportPolicy.issueUrl("PingRing 1.4 on Google Pixel 10", "a b")

        assertTrue(url.contains("%20"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun `non-ascii characters are encoded as utf-8 rather than mangled`() {
        // A device model with an accent, or a reporter's own text in the body.
        val url = BugReportPolicy.issueUrl("t", "café ✓")

        assertEquals("café ✓", bodyOf(url))
    }

    @Test
    fun `an over-long report drops the body rather than truncating it`() {
        // Half a report is the dangerous outcome: the member would believe their
        // diagnostics were attached. An empty editor they can see is not.
        val url = BugReportPolicy.issueUrl("PingRing 1.4", "x".repeat(20_000))

        assertFalse(url.contains("body="))
        assertTrue(url.startsWith(BugReportPolicy.NEW_ISSUE_URL))
        assertEquals("PingRing 1.4", titleOf(url))
    }

    @Test
    fun `a report that just fits keeps its body`() {
        // The boundary in the other direction. A limit that threw away a
        // legitimate report would be a silent failure too.
        val padding = BugReportPolicy.MAX_URL_LENGTH -
            BugReportPolicy.issueUrl("t", "").length - 10
        val url = BugReportPolicy.issueUrl("t", "y".repeat(padding))

        assertTrue(url.contains("body="))
    }

    @Test
    fun `the title names the version and the device`() {
        // "It works on my phone" is asked of nearly every report, so the answer
        // goes where a triager can see it without expanding the body.
        assertEquals(
            "PingRing 1.4 on Google Pixel 10",
            BugReportPolicy.titleFor("1.4", "Google Pixel 10"),
        )
    }

    @Test
    fun `the url points at the public tracker and nowhere else`() {
        // PingRing has no backend. This is the assertion that keeps it that way.
        assertEquals(
            "https://github.com/fkurevija/PingRing/issues/new",
            BugReportPolicy.NEW_ISSUE_URL,
        )
        assertTrue(BugReportPolicy.issueUrl("t", "b").startsWith("https://"))
    }

    @Test
    fun `an empty report still produces a usable link`() {
        val url = BugReportPolicy.issueUrl("", "")

        assertTrue(url.startsWith(BugReportPolicy.NEW_ISSUE_URL))
        assertEquals("", bodyOf(url))
    }
}