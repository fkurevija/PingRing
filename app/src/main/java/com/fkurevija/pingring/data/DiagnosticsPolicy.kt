package com.fkurevija.pingring.data

/**
 * The facts an About screen shows, and the plain-text report a member can paste
 * into a bug report.
 *
 * ## The privacy shape is the design, not a filter
 *
 * [DiagnosticsFacts] is a closed set of *typed* fields about the build, the
 * hardware and the radio state. There is no `String` field that a name, an event
 * code, a coordinate or a peer id could be put into, so there is nothing here
 * for a privacy mistake to leak.
 *
 * That is deliberate. PingRing's logs already exclude chat text, beacon titles,
 * payload JSON and STS keys (see `docs/security-review.md`), and an About screen
 * is a place where such a leak would be *shown to a human* and then pasted into
 * a public issue tracker. A filter at the end would be one forgotten branch away
 * from a regression; a whitelist at the type level cannot be bypassed by a new
 * field, because adding the field means adding it here first.
 *
 * Pure and Android-free, so the formatting and ordering are testable without a
 * device. Probing the platform is [DiagnosticsProbe]'s job.
 */
object DiagnosticsPolicy {

    /**
     * One reported fact.
     *
     * A [key] rather than a label string, so the label stays localizable and
     * this stays free of Android resources.
     */
    data class Row(val key: Key, val value: String)

    /** What a [Row] is about. The screen maps each to a localized label. */
    enum class Key {
        APP_VERSION,
        BUILD_TYPE,
        APPLICATION_ID,
        DEVICE,
        ANDROID_VERSION,
        BUILD,
        UWB_SUPPORTED,
        SIGNED_BLE_IDENTITY,
        BLUETOOTH_ENABLED,
        WIFI_ENABLED,
        VIBRATOR,
    }

    /**
     * [facts] as ordered rows.
     *
     * Grouped build, then device, then capabilities, which is the order somebody
     * reporting a bug reads them in. Never empty and never partial: a row that
     * cannot be determined is reported as unknown rather than omitted, because a
     * gap in a pasted report is indistinguishable from a feature not existing.
     */
    fun rows(facts: DiagnosticsFacts): List<Row> = listOf(
        Row(Key.APP_VERSION, "${facts.versionName} (${facts.versionCode})"),
        Row(Key.BUILD_TYPE, if (facts.isDebugBuild) "Debug" else "Release"),
        Row(Key.APPLICATION_ID, facts.applicationId),
        Row(Key.DEVICE, "${facts.deviceManufacturer} ${facts.deviceModel}".trim()),
        Row(Key.ANDROID_VERSION, "${facts.androidRelease} (API ${facts.apiLevel})"),
        Row(Key.BUILD, facts.buildId),
        Row(Key.UWB_SUPPORTED, yesNo(facts.uwbSupported)),
        Row(Key.SIGNED_BLE_IDENTITY, yesNo(facts.signedBleIdentitySupported)),
        Row(Key.BLUETOOTH_ENABLED, yesNo(facts.bluetoothEnabled)),
        Row(Key.WIFI_ENABLED, yesNo(facts.wifiEnabled)),
        Row(Key.VIBRATOR, yesNo(facts.hasVibrator)),
    )

    /**
     * The whole report as plain text, for the clipboard.
     *
     * One `key: value` per line, no heading and no preamble, so it pastes into
     * an issue template without dragging formatting along. Every line comes from
     * [rows] - this cannot emit anything [rows] does not, which is what makes
     * the whitelist above worth having.
     */
    fun report(facts: DiagnosticsFacts, label: (Key) -> String): String =
        rows(facts).joinToString("\n") { row -> "${label(row.key)}: ${row.value}" }

    /**
     * A boolean, as a word.
     *
     * English, deliberately: this string is destined for a bug tracker, not for
     * a screen. Localizing it would mean a French report pasting "Non" into a
     * template that expects a language-neutral token, and a capability fact has
     * no ambiguity for a reader to resolve.
     */
    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
}
    /**
 * Everything the About screen may show. Every field is a build, hardware or
 * radio fact. Nothing here is a name, a code, a coordinate or anything a person
 * did - see [DiagnosticsPolicy].
 *
 * @param uwbSupported whether this device has UWB hardware at all. Distinct from
 *   whether ranging is switched on, which is a per-ring choice shown elsewhere.
 * @param signedBleIdentitySupported whether the controller can send the extended
 *   V5 signed advertisement. Its absence silently disables RSSI-based presence,
 *   which is exactly the kind of thing a bug report is missing.
 */
data class DiagnosticsFacts(
    val versionName: String,
    val versionCode: Int,
    val isDebugBuild: Boolean,
    val applicationId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val buildId: String,
    val uwbSupported: Boolean,
    val signedBleIdentitySupported: Boolean,
    val bluetoothEnabled: Boolean,
    val wifiEnabled: Boolean,
    val hasVibrator: Boolean,
)

/**
 * Reads [DiagnosticsFacts] off the platform.
 *
 * The one place allowed to touch Build or a package manager. Separated from the
 * policy so the formatting rules stay assertable with no device attached.
 */
object DiagnosticsProbe {

    @Suppress("FunctionName")
    fun read(
        applicationId: String,
        versionName: String,
        versionCode: Int,
        isDebugBuild: Boolean,
        uwbSupported: Boolean,
        signedBleIdentitySupported: Boolean,
        bluetoothEnabled: Boolean,
        wifiEnabled: Boolean,
        hasVibrator: Boolean,
    ): DiagnosticsFacts = DiagnosticsFacts(
        versionName = versionName,
        versionCode = versionCode,
        isDebugBuild = isDebugBuild,
        applicationId = applicationId,
        deviceManufacturer = android.os.Build.MANUFACTURER,
        deviceModel = android.os.Build.MODEL,
        androidRelease = android.os.Build.VERSION.RELEASE,
        apiLevel = android.os.Build.VERSION.SDK_INT,
        buildId = android.os.Build.ID,
        uwbSupported = uwbSupported,
        signedBleIdentitySupported = signedBleIdentitySupported,
        bluetoothEnabled = bluetoothEnabled,
        wifiEnabled = wifiEnabled,
        hasVibrator = hasVibrator,
    )
}