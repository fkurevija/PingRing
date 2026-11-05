package com.combustiblelemonlabs.pingring.data

/**
 * Builds the URL that opens a pre-filled bug report on the issue tracker.
 *
 * ## Why a URL rather than a form or a backend
 *
 * PingRing sends nothing anywhere. There is no endpoint to POST a report to, and
 * adding one would trade the offline property for a feature the issue tracker
 * already does better. GitHub's `/issues/new` accepts `title` and `body` as
 * query parameters, so the whole report can travel inside the link itself and
 * land in the editor already filled in.
 *
 * That is strictly better than making the member copy the report and paste it:
 * the paste step is where reports get truncated, where "1.O" for "1.0" creeps
 * in, and where a member quietly gives up. The clipboard stays available on the
 * screen for anyone who wants the text for a chat or an email instead.
 *
 * Pure and Android-free, so the encoding is testable without a browser: every
 * character that has to survive a round trip through a query string is the
 * subject of a test rather than something discovered from a mangled issue.
 */
object BugReportPolicy {

    /** Where reports go. The public tracker, never an in-app destination. */
    const val NEW_ISSUE_URL = "https://github.com/combustiblelemonlabs/PingRing/issues/new"

    /**
     * Longest URL built before the body is dropped.
     *
     * A browser will normally accept far more than this, but the report is short
     * and bounded, and a link that has been silently truncated by a router or a
     * chat client is worse than one that arrives without a body: the member
     * would have believed their diagnostics were attached when they were not.
     * Dropping the body fails loudly instead - the editor opens empty and they
     * can still type, or copy the report from the screen above.
     */
    const val MAX_URL_LENGTH = 6_000

    /**
     * A `/issues/new` link carrying [title] and [body].
     *
     * The body is omitted rather than cut in half if the result would exceed
     * [MAX_URL_LENGTH] - see the constant for why a truncated report is worse
     * than none.
     */
    fun issueUrl(title: String, body: String): String {
        val query = "title=" + encode(title)
        val withBody = "$NEW_ISSUE_URL?$query&body=" + encode(body)
        return if (withBody.length > MAX_URL_LENGTH) "$NEW_ISSUE_URL?$query" else withBody
    }

    /**
     * A title a triager can filter on without opening the issue.
     *
     * The build and the device, because "it works on my phone" is the first
     * question asked of almost every report and the answer should not require
     * expanding the body. Kept language-neutral for the same reason the report
     * values are - see [DiagnosticsPolicy].
     */
    fun titleFor(version: String, device: String): String = "PingRing $version on $device"

    /**
     * Percent-encodes [value] for use as a query-parameter value.
     *
     * Written out rather than delegating to `URLEncoder`, for two reasons that
     * both matter for this specific string. `URLEncoder` is a *form* encoder and
     * emits `+` for a space, which is correct in a form body and wrong in a URL
     * - it is legal here, but it renders as a literal plus in some editors.
     * And it is an Android-overloaded API that lint flags at this project's
     * minSdk, which is not something a diagnostics link should be tripping on.
     *
     * RFC 3986: everything outside the unreserved set is escaped, so a build id
     * containing a `+`, `&`, `#` or a newline cannot break out of the parameter
     * and truncate the URL at the first `&`.
     */
    private fun encode(value: String): String {
        val out = StringBuilder(value.length * 2)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}