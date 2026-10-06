package com.fkurevija.pingring.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.IntentFilter
import android.os.BatteryManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Log
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.uwb.*
import com.fkurevija.pingring.MainActivity
import com.fkurevija.pingring.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.util.UUID
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class RadarPeerUpdate(
    val peerId: String,
    val name: String,
    val rssi: Int?,
    val proximity: String, // "Hot", "Cold", "Unknown"
    val distance: Float?,  // in meters
    val azimuth: Float?,   // in degrees
    val elevation: Float?, // in degrees
    val worldDirectionX: Float?,
    val worldDirectionY: Float?,
    val worldDirectionZ: Float?,
    val lastSeen: Long,
    val signalWeak: Boolean = false,
    val meshOnlyPresence: Boolean = false,
    val meshPresenceLastSeenAtMillis: Long? = null,
    val lastUwbMeasurementAtMillis: Long? = null,
    val distanceSource: RangingSourcePolicy.RangingSource? = null,
    val distanceAccuracyMeters: Float? = null,
    val lastAdditionalRangingAtMillis: Long? = null,
    val directionIsLastKnown: Boolean = false,
    /**
     * When this peer reported that it joined the event, in epoch millis.
     *
     * Null when the peer has not said, which is the case for one whose advert
     * predates the field and for one seen only over the debug transport. The
     * distinction matters because [FocusPolicy] breaks ties by this value: a
     * peer with no timestamp has to sort as *unknown* rather than as
     * arbitrarily early, or a member on an older build would win the host's
     * default for the wrong reason.
     */
    val joinedAtMillis: Long? = null,
    /**
     * Whether this distance can be corroborated, and whether something is
     * plausibly in the way.
     *
     * Null until either source has produced a reading. [RangingConfidence] rather
     * than a boolean, because "no second opinion yet" and "a second opinion
     * that agrees" are different states and the radar shows them differently.
     */
    val rangingConfidence: RangingConfidence? = null
)

data class LastKnownPeerDirection(
    val peerId: String,
    val name: String,
    val worldDirectionX: Float,
    val worldDirectionY: Float,
    val distance: Float?,
    val lastDetectedAtMillis: Long,
    val recordedAtMillis: Long,
)

data class ConnectivityStatus(
    val isBluetoothEnabled: Boolean,
    val isWifiEnabled: Boolean,
    val supportsBleIdentity: Boolean = true,
)

/**
 * The bytes PingRing puts in the service-data field of its BLE advertisement,
 * and the flags packed into one of them.
 *
 * This is the one thing on the discovery path that has to agree between two
 * devices with no negotiation at all: a host advertises, a guest scans, and
 * there is no acknowledgement either way. A layout change that is not applied to
 * both sides presents as "this device never sees anybody", with nothing on either
 * phone saying why.
 *
 * It lives apart from [DiscoveryRepository] for the same reason the other policy
 * objects do - so the encoding can be round-tripped in a test without a radio.
 * The repository builds it on the way out and reads it on the way in, and
 * [encode] and [decode] are the two halves of one format.
 *
 * Layout, big-endian (the platform `ByteBuffer` default), version 3:
 *
 * ```
 * offset 0   1 byte   format version, see [VERSION]
 * offset 1   4 bytes  event hash, the event id's String.hashCode()
 * offset 5   1 byte   flags, see the FLAG_* constants
 * offset 6   8 bytes  the advertiser's peer id, UTF-8, NUL-padded
 * offset 14  6 bytes  the event code, UTF-8, NUL-padded
 * offset 20  8 bytes  when the advertiser joined the event, epoch millis
 * ```
 *
 * The join time is what lets "first to join" be answered from the advert
 * rather than guessed from discovery order. It costs eight bytes and it is
 * worth them: without it the host's default tracked member is whoever it
 * happened to hear first, so a member who joins late and stands nearest can be
 * picked over the person who has been here since the start. See
 * [FocusPolicy.resolveFocus].
 *
 * Eight bytes is a full epoch-millis Long rather than something smaller on
 * purpose. A 4-byte value would have to be relative - seconds since some shared
 * epoch nobody agrees on - and there is no such epoch here: peers only ever
 * meet over BLE, with no server to hand out a common start time and no
 * handshake wide enough to agree one. Truncating to 32 bits would work only by
 * comparing timestamps taken on different clocks, which is a bug waiting for
 * anyone whose phone is minutes off. The bytes are available: 8 of the 11
 * legacy headroom, leaving 3.
 *
 * This is the only layout. Version 1 - no version byte, 13 bytes, no event
 * code - was removed for the same reason, and version 2 never shipped outside
 * test devices, so there is nothing to keep reading. A [VERSION] other than
 * this one is ignored like any other advert this build does not understand.
 *
 * The version byte is therefore not about the past. It is what makes the next
 * layout change safe: a newer build's advert is skipped rather than decoded
 * into a plausible-looking event that does not exist. Without it, adding a
 * field would mean every older build read the new leading byte as part of the
 * fields it already knows.
 *
 * The peer-id and event-code fields are padded rather than length-prefixed
 * because both are generated at a fixed length ([SessionManager] makes exactly
 * [PEER_ID_BYTES] characters of each), which leaves every byte accounted for and
 * keeps the total at exactly [TOTAL_BYTES] - the number that has to stay under
 * the 31-byte legacy advertising limit (see [BleRangePolicy]). The padding is
 * whatever [ByteBuffer.allocate] leaves, i.e. NUL, so [decode] strips NULs
 * rather than relying on `trim`, which does not treat them as whitespace.
 *
 * The event code is what lets a guest that was *told* the ring find its host:
 * without it the scanner has a hash it cannot reverse and no way to join. The
 * display name is derived from the code rather than sent, because [Event]
 * already names every event "PingRing <code>" - one less field, and no chance of
 * two copies disagreeing.
 */
object BleAdvertisementPayload {

    /**
     * Current wire format, and the only one.
     *
     * Read as "is this advert mine, and do I understand it" rather than as a
     * record of what came before. Anything else is ignored, including the v1
     * layout that [decode] used to understand: see the format notes above for
     * why that path was dropped rather than kept.
     */
    const val VERSION = 4

    /** V4 legacy fixture size; the active V5 advertiser uses extended service data. */
    const val TOTAL_BYTES = 28

    /** Truncated HMAC-SHA256 tag: 88 bits within the established 28-byte payload. */
    const val AUTH_TAG_BYTES = 11
    const val JOINED_AT_BYTES = 8

    /** The host owns the event; members follow it. */
    const val FLAG_HOST = 1

    /** The host is closing the event; every member leaves. */
    const val FLAG_EVENT_CLOSED = 1 shl 2

    /** UWB ranging is available in this event. */
    const val FLAG_USE_UWB = 1 shl 3

    /** Wi-Fi Direct is available in this event. */
    const val FLAG_USE_WIFI = 1 shl 4

    /** The event has reached its member limit; a guest should not try to join. */
    const val FLAG_RING_FULL = 1 shl 5

    /** New members are still being admitted. */
    const val FLAG_ACCEPTS_NEW_MEMBERS = 1 shl 6

    /**
     * Every flag this build defines.
     *
     * Listed rather than derived by reflection so that [encode]'s callers, the
     * bit-overlap test and the range check all read from one place. A flag added
     * here and nowhere else is caught by the overlap test; a bit assembled at a
     * call site and never declared is caught by [assertFlagsFitTheByte].
     *
     * Bit 1 (track-host) and bit 7 (discoverable) were dropped from this list
     * rather than renumbered, so the bits that remain keep their wire values.
     * Both described host policy that has since been removed: choosing who to
     * track is now the local user's own call, and sessions are no longer
     * advertised as findable, because joining is by code or by scanning the
     * host's own QR code. Renumbering would silently reassign live bits to
     * different meanings for anything still holding an old advertisement.
     */
    val ALL_FLAGS = listOf(
        FLAG_HOST, FLAG_EVENT_CLOSED, FLAG_USE_UWB,
        FLAG_USE_WIFI, FLAG_RING_FULL, FLAG_ACCEPTS_NEW_MEMBERS,
    )

    /**
     * Fails if any flag has moved outside the single byte that carries them.
     *
     * The byte is written as `flags.toByte()`, which keeps the low eight bits and
     * throws the rest away. A ninth flag would therefore not fail: it would be
     * silently dropped on the way out, and the peer on the other side would read
     * the advert perfectly happily while never learning the new fact. That is the
     * worst shape of bug - a feature that appears to work and quietly does
     * nothing - and it costs nothing to rule out here.
     *
     * Called from [encode], so the failure lands in the build's own test run
     * rather than on somebody's phone at an event.
     */
    internal fun assertFlagsFitTheByte(flags: Int) {
        // Checked on the value being written, not on the constant list: encode
        // is handed a combination assembled at the call site, so a bit nobody
        // declared - the ninth flag, added by hand in one place - is exactly what
        // would vanish. Inspecting ALL_FLAGS instead would pass while the very
        // failure being guarded against went straight through.
        require(flags in 0..0xFF) {
            "advert flags must fit in one unsigned byte (0..255), but $flags does not; " +
                "the byte would be truncated and peers would never see those bits"
        }
    }

    private const val PEER_ID_BYTES = 4
    private const val EVENT_CODE_FIELD_BYTES = 3
    private const val PREFIX_BYTES = TOTAL_BYTES - AUTH_TAG_BYTES

    /** The event code's field width, which [SessionManager] must match exactly. */
    const val EVENT_CODE_BYTES = 6

    /** The byte a fixed-width field is padded with, as a Char for `trim`. */
    private const val NUL = '\u0000'

    /** What a decoded advertisement said, once the bytes are unpacked. */
    data class Decoded(
        val flags: Int,
        val peerId: String,
        /**
         * The event code, or null when the field came back empty.
         *
         * Null no longer means "a v1 advert": every advert this build reads is
         * v2 and carries the field. It means the bytes were there and held
         * nothing, which is what [DiscoveryRepository.buildServicePayload]
         * produces when there is no event to name - the field is zero-filled
         * rather than omitted, so absence cannot be told from an all-NUL read
         * except by being empty.
         *
         * Null is not the same as empty: it means "this advert cannot be joined
         * by browsing", and a caller has to fall back to asking for the code
         * rather than offering a join that cannot work.
         */
        val eventCode: String? = null,
        /**
         * When the advertiser joined, in epoch millis, or 0 when it did not say.
         *
         * 0 rather than null on purpose: the field is a fixed-width Long in a
         * fixed-width slot, so there is no such thing as an absent value here -
         * there is only a zero. A zero is also safe to treat as "unknown" rather
         * than as epoch 1970, which is why [FocusPolicy] sorts it last instead of
         * treating it as the earliest possible arrival.
         */
        val joinedAtMillis: Long = 0L,
    ) {
        fun has(flag: Int): Boolean = flags and flag != 0

        val isHost: Boolean get() = has(FLAG_HOST)
        val eventClosed: Boolean get() = has(FLAG_EVENT_CLOSED)
        val usesUwb: Boolean get() = has(FLAG_USE_UWB)
        val usesWifi: Boolean get() = has(FLAG_USE_WIFI)
        val isFull: Boolean get() = has(FLAG_RING_FULL)
        val acceptsNewMembers: Boolean get() = has(FLAG_ACCEPTS_NEW_MEMBERS)

        /**
         * The name to show for a discovered session.
         *
         * Derived rather than transmitted. [com.fkurevija.pingring.data.Event]
         * already names every event "PingRing <code>", so sending the code is
         * enough to render the name on the far side - which is what keeps the
         * advert inside the legacy size limit, and keeps two copies of a name
         * from being able to disagree.
         */
        val displayName: String? get() = eventCode?.let { "PingRing $it" }
    }

    /**
     * The unauthenticated locator fields of a legacy advert.
     *
     * The event code alone, with no policy flag: the code is what lets a guest
     * that was told the ring find its host, and nothing else in an advert from a
     * phone that is not yet a member is worth acting on.
     */
    data class DiscoveryHint(val eventCode: String?)

    /**
     * Packs the advertisement.
     *
     * The peer id and event code are truncated or space-padded to exactly their
     * field width rather than rejected, because both are generated at a fixed
     * length and a mismatch would mean the two ends disagree about the identity
     * scheme itself - which is worth surfacing, not something a peer can fix by
     * trying again.
     *
     * A code longer than its field is truncated, which is a silent failure worth
     * naming: a guest adopting a truncated code would fail to join with nothing
     * to say why. [SessionManager] generates exactly [EVENT_CODE_BYTES]
     * characters and a test asserts it, so this cannot happen quietly.
     */
    fun encode(
        flags: Int,
        peerIdBytes: ByteArray,
        eventCode: String? = null,
        joinedAtMillis: Long = 0L,
        inviteSecret: String,
    ): ByteArray {
        // Checked before the buffer is built: `flags.toByte()` on the line below
        // is exactly the silent truncation this prevents.
        assertFlagsFitTheByte(flags)
        require(peerIdBytes.size == 8) { "peer id must be exactly 8 bytes" }
        val peerId = String(peerIdBytes, StandardCharsets.UTF_8)
            .trimEnd(NUL, ' ')
        require(peerId.length == 8 && peerId.all(::isHexDigit)) {
            "peer id must be exactly 8 hexadecimal characters"
        }
        val codeBytes = eventCodeBytes(eventCode)
        val prefix = ByteBuffer.allocate(PREFIX_BYTES)
            .put(VERSION.toByte())
            .put(flags.toByte())
            .put(hexBytes(peerId))
            .put(codeBytes)
            .putLong(joinedAtMillis)
            .array()
        val authTag = authenticationTag(inviteSecret, prefix)
        return prefix + authTag
    }

    /**
     * The event code as a fixed-width, NUL-padded field.
     *
     * Zero-filled first so a short code is padded, then the code is copied into
     * the front and truncated at the field width: an over-long code would
     * otherwise push every later field out of the payload.
     */
    private fun eventCodeBytes(eventCode: String?): ByteArray {
        if (eventCode == null) return ByteArray(EVENT_CODE_FIELD_BYTES)
        require(eventCode.length == EVENT_CODE_BYTES && eventCode.all(::isHexDigit)) {
            "event code must be exactly 6 hexadecimal characters"
        }
        return hexBytes(eventCode)
    }

    /**
     * Unpacks an advertisement, or null if it is not one of ours.
     *
     * Two rejections, both returning null rather than a best guess. A short
     * buffer is the signature of a *different* app's PingRing-shaped service
     * UUID, or of a truncated read, and must be ignored rather than parsed:
     * reading past the end would either throw or, worse, decode neighbouring
     * fields as a peer id and put a phantom member on the radar. A buffer this
     * build does not understand - a later version, or the dropped v1 - fails
     * the version check for the same reason.
     */
    fun decode(serviceData: ByteArray, inviteSecret: String): Decoded? {
        if (serviceData.size < TOTAL_BYTES) return null
        if (serviceData[0].toInt() != VERSION) return null

        val prefix = serviceData.copyOfRange(0, PREFIX_BYTES)
        val storedTag = serviceData.copyOfRange(PREFIX_BYTES, TOTAL_BYTES)
        val expectedTag = runCatching { authenticationTag(inviteSecret, prefix) }.getOrNull()
            ?: return null
        if (!MessageDigest.isEqual(storedTag, expectedTag)) return null

        val payload = ByteBuffer.wrap(prefix)
        payload.get() // the version byte, already checked above
        val flags = payload.get().toInt()
        val peerId = bytesToHex(ByteArray(PEER_ID_BYTES).also(payload::get)).lowercase()
        val code = bytesToHex(ByteArray(EVENT_CODE_FIELD_BYTES).also(payload::get))
            .takeIf { it != "000000" }
        val joinedAtMillis = payload.long
        return Decoded(flags, peerId, code, joinedAtMillis)
    }

    fun decodeDiscoveryHint(serviceData: ByteArray): DiscoveryHint? {
        if (serviceData.size < TOTAL_BYTES || serviceData[0].toInt() != VERSION) return null
        val payload = ByteBuffer.wrap(serviceData)
        payload.position(1 + 1 + PEER_ID_BYTES)
        val code = bytesToHex(ByteArray(EVENT_CODE_FIELD_BYTES).also(payload::get))
            .takeIf { it != "000000" }
        return DiscoveryHint(code)
    }

    /**
     * Reads a fixed-width, NUL-padded field.
     *
     * Exactly the field width, never `remaining()`: a newer build that adds a
     * trailing field must not have those bytes folded into this one. `trim` does
     * not consider NUL whitespace, so they are stripped explicitly, and spaces
     * with them - a field carrying either is equally unusable as a routing key.
     */
    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char.lowercaseChar() in 'a'..'f'

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun authenticationTag(secret: String, prefix: ByteArray): ByteArray {
        val key = InviteSecret.decode(secret) ?: throw IllegalArgumentException("invalid invite secret")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(prefix).copyOf(AUTH_TAG_BYTES)
    }
}

@SuppressLint("MissingPermission")
class DiscoveryRepository(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val nearbyConnectionsManager: NearbyTransport,
    private val appearanceSettings: AppearanceSettingsRepository = AppearanceSettingsRepository(context)
) {

    private val TAG = "DiscoveryRepository"
    private val PINGRING_SERVICE_UUID = UUID.fromString("0000fa1b-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Expose connectivity status
    private val _connectivityStatus = MutableStateFlow(getConnectivityStatus())
    val connectivityStatus: StateFlow<ConnectivityStatus> = _connectivityStatus.asStateFlow()
    private val _hostConnectionLostAtMillis = MutableStateFlow<Long?>(null)
    val hostConnectionLostAtMillis: StateFlow<Long?> = _hostConnectionLostAtMillis.asStateFlow()

    // Expose peer updates
    private val _peerUpdates = MutableStateFlow<Map<String, RadarPeerUpdate>>(emptyMap())
    val peerUpdates: Flow<List<RadarPeerUpdate>> = _peerUpdates.map { it.values.toList() }

    /**
     * Everyone in the ring, whether or not they are currently in reach.
     *
     * The member list is rendered from this rather than from [peerUpdates], and
     * the difference is the whole bug. `_peerUpdates` is a cache of *live radar
     * readings*: a member not heard from for 15 s is swept out of it, which is
     * right for drawing a pin and wrong for listing who is in the ring. Rendering
     * the list from that cache made it empty whenever contact lapsed, so two
     * devices in one ring could each show a single member - themselves - while the
     * member count, derived from the same cache, agreed with it.
     *
     * A member with no fresh reading is still listed, with distance unknown.
     * Losing sight of somebody is not the same as losing them.
     */
    val members: Flow<List<RadarPeerUpdate>> =
        // Combines both sources deliberately. Membership alone would not
        // recompose when a distance arrives, and radar alone is the bug: a
        // member is listed because they are *in the ring*, and shown as close or
        // unknown according to whether there is a fresh reading for them.
        combine(sessionManager.sessionState, _peerUpdates) { state, live ->
            state.friends.map { friend ->
                // The radar reading if there is one, otherwise a bare entry
                // carrying only what membership knows: the peer and their name.
                // Never a stale distance, which would claim a reading the app no
                // longer has - the sweep has already removed those.
                live[friend.peerId] ?: RadarPeerUpdate(
                    peerId = friend.peerId,
                    name = friend.name,
                    // No reading at all, rather than a stale one: the sweep has
                    // already dropped everything out of date, so anything left here
                    // is a member the app cannot currently place. "Unknown" is the
                    // honest proximity for that, and the row shows their distance as
                    // unavailable instead of inventing a number.
                    rssi = null,
                    proximity = "Unknown",
                    distance = null,
                    azimuth = null,
                    elevation = null,
                    worldDirectionX = null,
                    worldDirectionY = null,
                    worldDirectionZ = null,
                    lastSeen = friend.lastSeen,
                )
            }
        }
    private val _lastKnownPeerDirections = MutableStateFlow<List<LastKnownPeerDirection>>(emptyList())
    val lastKnownPeerDirections: StateFlow<List<LastKnownPeerDirection>> = _lastKnownPeerDirections.asStateFlow()

    // Device orientation (heading, and the world direction of each peer). Kept in
    // its own class because it is the one part of discovery that keeps running
    // whenever the screen is visible, independently of whether a ring is active.
    private val orientationTracker = DeviceOrientationTracker(context).apply {
        onWorldVectorsChanged = { refreshPeerWorldDirections() }
    }
    val deviceHeading: StateFlow<Float> get() = orientationTracker.deviceHeading

    // Active Beacons map (peerId -> BeaconInfo)
    private val _activeBeacons = MutableStateFlow<Map<String, BeaconInfo>>(emptyMap())
    val activeBeacons: StateFlow<Map<String, BeaconInfo>> = _activeBeacons.asStateFlow()

    /**
     * Beacons that have ended, oldest first.
     *
     * A beacon is a point in the conversation once it is over, so it has to keep
     * a place in the transcript rather than vanishing when it stops. Active
     * beacons are current state and sit at the near end of the chat; this is
     * where the ones that have finished go, and the chat interleaves the two by
     * timestamp so a beacon ends up where it was raised rather than where it
     * happened to stop.
     *
     * Bounded because nothing else prunes it: a long PingRing would otherwise
     * grow this without limit. [HISTORY_LIMIT] is far more entries than a
     * session realistically produces, so in practice nothing is ever dropped
     * and this is a guard against a runaway rather than a policy.
     */
    private val _endedBeacons = MutableStateFlow<List<BeaconInfo>>(emptyList())
    val endedBeacons: StateFlow<List<BeaconInfo>> = _endedBeacons.asStateFlow()

    /** How many ended beacons to keep. See [_endedBeacons]. */
    private val historyLimit = 200

    /**
     * Moves a beacon from active to ended, keeping the copy the reader already
     * saw.
     *
     * The beacon that is ended is the one currently in [activeBeacons] for this
     * peer rather than anything in the end packet: the end DTO carries only
     * `isActive = false` and the identity, so using it would show a reader a
     * second, blank beacon at the end of the conversation.
     */
    private fun endBeacon(peerId: String) {
        val ending = _activeBeacons.value[peerId]
        _activeBeacons.update { it - peerId }
        if (ending == null) return
        // Stop the alert before anything else. A repeating alert is the first
        // beacon behaviour that can still be sounding when the beacon ends, and
        // a phone that keeps buzzing after "Regroup" has been called has failed at
        // the one job the alert had.
        stopVibration()
        _endedBeacons.update { history ->
            // Keyed by beacon id so a repeated end for the same beacon - a peer
            // that disconnects while beaconing, then reconnects - appends a
            // duplicate rather than two identical entries in the transcript.
            if (history.any { it.id == ending.id }) {
                history
            } else {
                (history + ending.copy(isActive = false)).takeLast(historyLimit)
            }
        }
    }

    // Local Beacon state (null if not active)
    private val _localBeacon = MutableStateFlow<BeaconInfo?>(null)
    val localBeacon: StateFlow<BeaconInfo?> = _localBeacon.asStateFlow()

    /**
     * How this device alerts for beacons, seeded from the stored setting and
     * then kept current.
     *
     * Held as a plain value rather than collected at each playback because
     * `playBeaconAlert` is called from the beacon receive path, which is not a
     * suspending context; reading a cached value there keeps the alert decision
     * synchronous and identical on the sender's phone and on every recipient's.
     *
     * Seeded to the default rather than left null so the very first beacon in a
     * fresh process alerts the way the member configured it last time, instead of
     * waiting for a DataStore read that may not have landed yet.
     */
    private val _beaconAlertSettings = MutableStateFlow(BeaconAlertSettings.DEFAULT)
    val beaconAlertSettings: StateFlow<BeaconAlertSettings> = _beaconAlertSettings.asStateFlow()

    /**
     * Beacons this device has been told to stop alerting for.
     *
     * Read by [playBeaconAlert] and by the UI, never sent anywhere. It suppresses
     * alerts only - a silenced beacon is still drawn, still counts as active and
     * still ends when its sender stops it.
     */
    private val _silencedBeaconIds = MutableStateFlow<Set<String>>(emptySet())
    val silencedBeaconIds: StateFlow<Set<String>> = _silencedBeaconIds.asStateFlow()

    /**
     * Devices waiting on the host to approve them, oldest first.
     *
     * Exposed so the radar can show the host what is waiting without the screen
     * needing the transport itself; the transport is an implementation detail of
     * this repository and the screen has no business reaching past it.
     */
    /** Devices waiting to be let in, oldest first. */
    val pendingJoinRequests get() = nearbyConnectionsManager.pendingJoinRequests

    /** Lets a held join request in. */
    fun approveJoinRequest(endpointId: String) = nearbyConnectionsManager.approveJoinRequest(endpointId)

    /** Turns a held join request away. */
    fun denyJoinRequest(endpointId: String) = nearbyConnectionsManager.denyJoinRequest(endpointId)

    /** Sends a confirmed guest departure before its authenticated host link closes. */
    fun announceMemberDeparture(onComplete: () -> Unit = {}) {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent
        if (state.isHost || event == null) {
            onComplete()
            return
        }
        nearbyConnectionsManager.announceMemberDeparture(
            departure = MemberDeparturePayloadDto(eventCode = event.id, peerId = sessionManager.peerId.value),
            onComplete = {
                android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete)
            },
        )
    }

    val pendingAuthentications get() = nearbyConnectionsManager.pendingAuthentications

    fun confirmConnectionAuthentication(endpointId: String) =
        nearbyConnectionsManager.confirmConnectionAuthentication(endpointId)

    fun rejectConnectionAuthentication(endpointId: String) =
        nearbyConnectionsManager.rejectConnectionAuthentication(endpointId)

    // Local peer ID
    val localPeerId: String get() = sessionManager.peerId.value

    // How an incoming chat message reaches the user, kept out of this class
    // because it is the only part of chat that touches the notification system.
    private val chatNotifier = ChatNotifier(
        context = context,
        appearanceSettings = appearanceSettings,
        currentEventName = { sessionManager.sessionState.value.currentEvent?.name },
    )

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var repositoryScope: CoroutineScope? = null
    // Tracks which event/peer/role discovery is currently running for, so a
    // redundant startDiscovery() call for the exact same session (e.g. both
    // HostSetupScreen and RadarScreen call this when navigating between
    // them) doesn't tear down and re-establish transport connections it
    // doesn't need to — that churn was dropping in-flight chat messages.
    //
    // The radio settings are part of the identity on purpose: flipping the UWB
    // or Wi-Fi switch on the host screen really does have to rebuild the
    // subsystems that were started with the old values. What must never happen
    // is a restart when nothing changed, which is the common case: the service,
    // the host screen and the radar screen each call this on their own, and
    // restarting tore the transport down and dropped every live connection.
    private var activeDiscoveryEventId: String? = null
    private var activeDiscoveryPeerId: String? = null
    private var activeDiscoveryInviteSecret: String? = null
    private var activeDiscoveryIsHost: Boolean? = null
    private var activeDiscoveryUseUwb: Boolean? = null
    private var activeDiscoveryUseWifiDirect: Boolean? = null
    private var activeDiscoveryUseHotspot: Boolean? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisingActive = false
    private var signedBleAdvertiser: BluetoothLeAdvertiser? = null
    private var signedAdvertisingSet: AdvertisingSet? = null
    private var signedAdvertisingCallback: AdvertisingSetCallback? = null
    private var signedAdvertisementRefreshJob: Job? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val controlScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Declared here rather than next to the flow above because it has to come
    // after controlScope: Kotlin runs initialisers in declaration order, and this
    // one launches on it.
    init {
        controlScope.launch {
            appearanceSettings.beaconAlertSettings.collect { stored ->
                _beaconAlertSettings.value = stored
            }
        }
        controlScope.launch {
            appearanceSettings.silencedBeaconIds.collect { stored ->
                _silencedBeaconIds.value = stored
            }
        }
    }
    private var localBleIdentityKeyPair: java.security.KeyPair? = null
    private var localBleIdentityKey: BleIdentityKeyPayloadDto? = null
    private val bleIdentityKeys = java.util.concurrent.ConcurrentHashMap<String, BleIdentityKeyPayloadDto>()
    private val latestBleAdvertisementSequences = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val additionalRangingSequences = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val peerGnssFixes = java.util.concurrent.ConcurrentHashMap<String, Pair<GnssFixDto, Long>>()
    private val localRangingSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val gnssRanging = GnssRangingManager(context) { fix ->
        publishPeerRanging(gnssFix = fix)
        updateGnssDistances()
    }
    private val wifiRttRanging = WifiAwareRangingManager(
        context,
        focusedPeer = { resolveFocusedPeer() },
        hasFreshUwb = { peerId -> hasFreshUwbMeasurement(peerId) },
        onAddress = { mac -> publishPeerRanging(wifiAwareMac = mac) },
        onDistance = { peerId, distance, deviation ->
            updateAdditionalDistance(peerId, distance, deviation, RangingSourcePolicy.RangingSource.WIFI_RTT)
        },
    )
    private val nextBleAdvertisementSequence = java.util.concurrent.atomic.AtomicLong()

    // UWB objects
    private var uwbManager: UwbManager? = null
    private var uwbSessionJob: Job? = null
    private val activeRangingJobs = mutableMapOf<String, Job>()

    // Peers that have a real UWB measurement, whose distance must not be
    // overwritten by an RSSI estimate. Tracked explicitly because a UWB
    // report can legitimately carry a null azimuth or elevation, so the
    // presence of those fields is not a reliable "has been ranged" signal.
    private val uwbRangedPeers = mutableSetOf<String>()

    // Running RSSI distance estimate per peer, fed through an exponential
    // moving average so the readout does not jitter with every advertisement.
    // Rolling RSSI average per peer, so the radar does not show a distance that
    // twitches with every advert. One instance per peer: the readings of eight
    // members in a ring must not average into each other.
    private val rssiSmoothers = mutableMapOf<String, RssiSmoother>()
    private val bleSignalWarningTracker = BleSignalWarningTracker()

    /**
     * The member the user has chosen to track, or null for "auto" (nearest).
     *
     * This is a *view and ranging* choice only. It deliberately does not
     * filter incoming traffic: every member still receives every message
     * and beacon, so narrowing the radar never silences anyone.
     */
    private val _focusedPeerId = MutableStateFlow<String?>(null)
    val focusedPeerId: StateFlow<String?> = _focusedPeerId.asStateFlow()

    /** Admitted members recently seen over a direct link or signed mesh presence. */
    private val connectedPeerIds = mutableSetOf<String>()
    private val departedPeerIds = mutableSetOf<String>()

    /**
     * Which member holds which chat bubble colour, keyed colour to peer.
     *
     * On the host this is the authority it answers claims from. On a guest it is
     * what the host last told it, so the picker greys out the same swatches
     * everywhere - which is the entire point, since two members picking the same
     * colour is the confusion being prevented.
     *
     * A member appears at most once: switching colour releases the old one, so
     * the map never holds a colour for somebody who is no longer rendering in it.
     */
    private val _chatColourHolders = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatColourHolders: StateFlow<Map<Long, String>> = _chatColourHolders.asStateFlow()

    /**
     * Asks the host for [colorArgb], or reports a refusal locally.
     *
     * The pick is *not* applied optimistically. Applying it and then reverting on
     * a refusal would show a member a colour for as long as the round trip took,
     * which is exactly the window in which two members think they share one.
     * Instead the local value changes only when the host agrees.
     */
    fun requestChatColour(colorArgb: Long, onResult: (Boolean) -> Unit = {}) {
        val eventId = sessionManager.sessionState.value.currentEvent?.id
        if (eventId == null) {
            onResult(false)
            return
        }
        val localPeerId = sessionManager.peerId.value
        val state = sessionManager.sessionState.value

        if (state.isHost) {
            // The host is the authority; asking it would be asking itself.
            applyGrantedChatColour(colorArgb, localPeerId)
            onResult(true)
            return
        }

        nearbyConnectionsManager.broadcastChatColourClaim(
            ChatColourClaimPayloadDto(
                eventCode = eventId,
                peerId = localPeerId,
                colorArgb = colorArgb,
                granted = null,
                requestedAtMillis = System.currentTimeMillis(),
            )
        )
        // Not persisted until the host answers, so a refused pick does not leave a
        // member rendering in a colour they do not hold.
        chatColourRequests[colorArgb] = onResult
    }

    /**
     * The callbacks waiting on the host's answer, keyed by the colour claimed.
     *
     * Held so a guest can tell "refused" from "never heard back" - the first can
     * be reported and the picker can fall back, the second is a lost message and
     * must not leave the member believing they hold the colour.
     */
    private val chatColourRequests = java.util.concurrent.ConcurrentHashMap<Long, (Boolean) -> Unit>()

    /**
     * Applies a granted colour locally: records the holder and releases whatever
     * this member held before.
     */
    private fun applyGrantedChatColour(colorArgb: Long, peerId: String) {
        _chatColourHolders.update { current ->
            ChatColourPolicy.applyGrantedClaim(current, colorArgb, peerId)
        }
    }

    /**
     * One claim, resolved by whoever is in a position to.
     *
     * The host decides and announces; a guest only listens for the answer to its
     * own claim. A guest ignoring another guest's claim is deliberate: two guests
     * are not in a position to arbitrate between each other, and letting them
     * would reintroduce exactly the race the host exists to remove.
     */
    private fun handleChatColourClaim(claim: ChatColourClaimPayloadDto) {
        val state = sessionManager.sessionState.value
        val eventId = state.currentEvent?.id ?: return
        if (claim.eventCode != eventId) return
        val localPeerId = sessionManager.peerId.value
        val colorArgb = claim.colorArgb

        if (state.isHost) {
            if (colorArgb == null) return
            val decision = ChatColourPolicy.evaluateClaim(
                claimColorArgb = colorArgb,
                claimPeerId = claim.peerId,
                currentHolders = _chatColourHolders.value,
                localPeerId = localPeerId,
            )
            if (decision == ChatColourPolicy.Decision.GRANTED) {
                applyGrantedChatColour(colorArgb, claim.peerId)
            }
            // Always answered, granted or not. Silence would leave the member
            // waiting with no way to tell a refusal from a lost message.
            nearbyConnectionsManager.broadcastChatColourClaim(
                ChatColourClaimPayloadDto(
                    eventCode = eventId,
                    peerId = claim.peerId,
                    colorArgb = colorArgb,
                    granted = decision == ChatColourPolicy.Decision.GRANTED,
                    requestedAtMillis = claim.requestedAtMillis,
                )
            )
            return
        }

        // Guest: only our own claim is answered.
        if (claim.peerId != localPeerId || claim.granted == null || colorArgb == null) return
        if (claim.granted) {
            applyGrantedChatColour(colorArgb, localPeerId)
        }
        // Remove the callback whether granted or refused: this is the answer, and
        // leaving it would hold onto a lambda the caller is waiting on.
        chatColourRequests.remove(colorArgb)?.invoke(claim.granted)
    }

    fun setFocusedPeer(peerId: String?) {
        _focusedPeerId.value = peerId
        Log.i(TAG, "Radar focus set to ${peerId ?: "auto (nearest)"}")
        onFocusChanged()
    }

    /**
     * Resolves the focused member, or null when there is nobody to focus on.
     */
    fun resolveFocusedPeer(): String? {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return null
        return FocusPolicy.resolveFocus(
            selectedPeerId = _focusedPeerId.value,
            // The map is insertion-ordered, so this is discovery order. It is
            // only the tie-break now: a peer that reported when it joined is
            // ranked ahead of one that did not.
            availablePeerIds = _peerUpdates.value.keys.toList(),
            peerJoinedAt = _peerUpdates.value.mapValues { (_, peer) -> peer.joinedAtMillis },
            isHost = state.isHost,
            localPeerId = sessionManager.peerId.value,
            hostPeerId = event.hostPeerId
        )
    }

    init {
        if (context.packageManager.hasSystemFeature("android.hardware.uwb")) {
            try {
                uwbManager = UwbManager.createInstance(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UWB Manager", e)
            }
        }
    }

    fun getConnectivityStatus(): ConnectivityStatus {
        val bleEnabled = bluetoothAdapter?.isEnabled ?: false
        val wifiEnabled = wifiManager?.isWifiEnabled ?: false
        return ConnectivityStatus(
            isBluetoothEnabled = bleEnabled,
            isWifiEnabled = wifiEnabled,
            supportsBleIdentity = bleEnabled && BleRangePolicy.supportsSignedBleIdentityAdvertising(context),
        )
    }

    fun refreshConnectivityStatus() {
        _connectivityStatus.value = getConnectivityStatus()
    }

    fun isUwbSupported(): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.uwb")
    }

    fun startDiscovery() {
        val event = sessionManager.sessionState.value.currentEvent
        val localPeerId = sessionManager.peerId.value
        val isHost = sessionManager.sessionState.value.isHost

        // The host's admission switch has to be applied even when discovery is
        // already running: the host screen calls this again on every toggle, and
        // restarting the transport just to apply a switch would drop the peers
        // that are already connected.
        if (event != null) {
            nearbyConnectionsManager.acceptsNewMembers = event.acceptsNewMembers
        }

        if (event != null &&
            event.id == activeDiscoveryEventId &&
            localPeerId == activeDiscoveryPeerId &&
            event.inviteSecret == activeDiscoveryInviteSecret &&
            isHost == activeDiscoveryIsHost &&
            event.useUwb == activeDiscoveryUseUwb &&
            event.useWifiDirect == activeDiscoveryUseWifiDirect &&
            event.useHotspot == activeDiscoveryUseHotspot &&
            repositoryScope?.isActive == true
        ) {
            // Already actively discovering for this exact session — skip the
            // teardown/restart to avoid unnecessary transport reconnects.
            Log.d(TAG, "Discovery already active for event ${event.id}, ignoring duplicate startDiscovery()")
            return
        }

        // Tear down whatever was running *before* recording the new state.
        // stopDiscovery() clears these markers, so setting them first wiped them
        // again and left the guard above permanently broken: every call became a
        // full stop/start of the transport. That is not cosmetic - stopping
        // clears connectedEndpoints and tears down the Nearby listeners, so a
        // guest's requestConnection would land on a host that was momentarily
        // not listening (ACCEPT_CONNECTION_FAILED / SOCKET_CLOSED), the peer
        // list on the radar comes from BLE so it still looked fine, and no
        // message could ever be delivered.
        stopDiscovery()
        activeDiscoveryEventId = event?.id
        activeDiscoveryPeerId = localPeerId
        activeDiscoveryInviteSecret = event?.inviteSecret
        activeDiscoveryIsHost = isHost
        activeDiscoveryUseUwb = event?.useUwb
        activeDiscoveryUseWifiDirect = event?.useWifiDirect
        activeDiscoveryUseHotspot = event?.useHotspot
        _hostConnectionLostAtMillis.value = null
        if (event != null) {
            _lastKnownPeerDirections.value = emptyList()
            val keyPair = BleIdentityCrypto.generateKeyPair()
            localBleIdentityKeyPair = keyPair
            localBleIdentityKey = BleIdentityKeyPayloadDto(
                eventCode = event.id,
                peerId = localPeerId,
                publicKey = BleIdentityCrypto.encodePublicKey(keyPair.public),
            ).also { identity ->
                bleIdentityKeys.clear()
                latestBleAdvertisementSequences.clear()
                nextBleAdvertisementSequence.set(0L)
                bleIdentityKeys[localPeerId] = identity
            }
        }
        repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        refreshConnectivityStatus()

        // 1. Start Nearby Connections
        if (event != null) {
            // Lets the transport route a guest's UWB ranging offer to the host
            // without this class reaching into the session itself.
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.hostPeerIdProvider = {
                sessionManager.sessionState.value.currentEvent?.hostPeerId
            }
            nearbyConnectionsManager.inviteSecretProvider = {
                sessionManager.sessionState.value.currentEvent?.inviteSecret
            }
            nearbyConnectionsManager.userNameProvider = {
                sessionManager.sessionState.value.userName
            }
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.meshIdentityKeyProvider = { peerId ->
                bleIdentityKeys[peerId]
            }
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.meshPrivateKeyProvider = {
                localBleIdentityKeyPair?.private
            }
            if (isHost) {
                nearbyConnectionsManager.startAdvertising(
                    event.id, 
                    localPeerId, 
                    event.useWifiDirect, 
                    event.useHotspot
                )
            } else {
                nearbyConnectionsManager.startDiscovery(event.id, localPeerId)
            }
        }

        if (event != null && nearbyConnectionsManager is NearbyConnectionsManager) {
            wifiRttRanging.start(event.id)
        }
        repositoryScope?.launch {
            sessionManager.sessionState.map { it.currentEvent?.useGnss == true }.distinctUntilChanged().collect { enabled ->
                gnssRanging.setEnabled(enabled)
                if (!enabled) {
                    peerGnssFixes.clear()
                    clearAdditionalDistances(RangingSourcePolicy.RangingSource.GNSS)
                }
            }
        }
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedPeerRanging.collect { payload ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (payload.eventCode != current.id || payload.peerId == localPeerId() ||
                    payload.peerId in departedPeerIds || payload.sequence < 0
                ) return@collect
                var accepted = false
                additionalRangingSequences.compute(payload.peerId) { _, previous ->
                    if (previous == null || payload.sequence > previous) {
                        accepted = true
                        payload.sequence
                    } else previous
                }
                if (!accepted) return@collect
                payload.wifiAwareMac?.let { wifiRttRanging.registerPeer(payload.peerId, it) }
                val fix = payload.gnssFix
                if (current.useGnss && fix != null && GnssPolicy.isUsable(fix)) {
                    peerGnssFixes[payload.peerId] = fix to android.os.SystemClock.elapsedRealtime()
                    updateGnssDistances()
                }
            }
        }

        // 2. Start Periodic Peer Cleanup
        repositoryScope?.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                expireAdditionalDistances(now)
                refreshPeerWorldDirections()
                val expiredPeerIds = _peerUpdates.value.values
                    .filter { peer ->
                        now - maxOf(peer.lastSeen, peer.meshPresenceLastSeenAtMillis ?: 0L) >= PEER_EXPIRY_TIMEOUT_MILLIS &&
                            peer.peerId !in retainedRangingPeerIds
                    }
                    .map { it.peerId }
                expiredPeerIds.forEach(::rememberLastKnownPeerDirection)
                connectedPeerIds.removeAll(expiredPeerIds.toSet())
                expiredPeerIds.forEach(peerUwbAddresses::remove)
                val state = sessionManager.sessionState.value
                if (!state.isHost && state.currentEvent?.hostPeerId in expiredPeerIds &&
                    _hostConnectionLostAtMillis.value == null
                ) {
                    _hostConnectionLostAtMillis.value = now
                }
                _peerUpdates.update { current ->
                    current.filter { (peerId, peer) ->
                        now - maxOf(peer.lastSeen, peer.meshPresenceLastSeenAtMillis ?: 0L) < PEER_EXPIRY_TIMEOUT_MILLIS ||
                            peerId in retainedRangingPeerIds
                    }
                }
                _lastKnownPeerDirections.update { locations ->
                    locations.filter {
                        UwbRetentionPolicy.isLastKnownDirectionWithinWindow(it.recordedAtMillis, now)
                    }
                }
            }
        }

        // 2b. Start Chat Message Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedInviteSecret.collect { payload ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (sessionManager.sessionState.value.isHost || payload.eventCode != current.id) return@collect
                if (InviteSecret.decode(payload.inviteSecret) == null) return@collect
                if (current.inviteSecret != null && current.inviteSecret != payload.inviteSecret) return@collect
                if (payload.hostPeerId.isBlank()) return@collect
                sessionManager.updateEvent(
                    current.copy(inviteSecret = payload.inviteSecret, hostPeerId = payload.hostPeerId)
                )
                _hostConnectionLostAtMillis.value = null
                addConnectedPeer(payload.hostPeerId)
                activeDiscoveryInviteSecret = payload.inviteSecret
                localBleIdentityKey?.let { identityKey ->
                    nearbyConnectionsManager.sendBleIdentityKey(payload.hostPeerId, identityKey)
                }
                stopBleAdvertising()
                stopBleScanning()
                startSignedBleAdvertising()
                startBleScanning()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedBleIdentityKey.collect { identityKey ->
                val currentEvent = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (identityKey.eventCode != currentEvent.id || identityKey.peerId == sessionManager.peerId.value) {
                    return@collect
                }
                if (BleIdentityCrypto.decodePublicKey(identityKey.publicKey) == null) return@collect
                val previous = bleIdentityKeys.put(identityKey.peerId, identityKey)
                if (previous?.publicKey != identityKey.publicKey) {
                    latestBleAdvertisementSequences.remove(identityKey.peerId)
                }
                (nearbyConnectionsManager as? NearbyConnectionsManager)?.connectToPeer(identityKey.peerId)
                if (sessionManager.sessionState.value.isHost) {
                    nearbyConnectionsManager.broadcastBleIdentityKey(identityKey)
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatColourClaim.collect { claim ->
                handleChatColourClaim(claim)
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMemberDeparture.collect { departure ->
                val currentEvent = sessionManager.sessionState.value.currentEvent ?: return@collect
                // The rule that separates losing contact from leaving lives in
                // MembershipPolicy, so it has one place to be asserted rather
                // than being re-derived here every time this path is touched.
                if (!MembershipPolicy.leavesRingOnGracefulDeparture(
                        departureEventCode = departure.eventCode,
                        currentEventCode = currentEvent.id,
                        departurePeerId = departure.peerId,
                        localPeerId = sessionManager.peerId.value,
                    )
                ) {
                    return@collect
                }
                departedPeerIds.add(departure.peerId)
                connectedPeerIds.remove(departure.peerId)
                peerUwbAddresses.remove(departure.peerId)
                removePeer(departure.peerId, preserveLastKnownDirection = false)
                // removePeer deliberately leaves membership alone, because losing
                // contact is not leaving. This is the case that *is* leaving, and
                // an authenticated graceful departure is the only thing short of
                // the host ending the ring that removes somebody from the list.
                val remaining = sessionManager.sessionState.value.friends
                    .filterNot { it.peerId == departure.peerId }
                if (remaining.size != sessionManager.sessionState.value.friends.size) {
                    sessionManager.updateFriends(remaining)
                }
                peerDisplayNames.forget(departure.peerId)
                // Their colour goes back too. Otherwise a departed member holds it
                // for the rest of the event, and with eight members and twelve
                // colours four of them would be gone for good.
                if (sessionManager.sessionState.value.isHost) {
                    _chatColourHolders.update { current ->
                        ChatColourPolicy.releaseDepartedMembers(
                            current,
                            remainingPeerIds = connectedPeerIds - departure.peerId + localPeerId(),
                        )
                    }
                }
                onFocusChanged()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMeshPresence.collect { presence ->
                if (presence.eventCode == sessionManager.sessionState.value.currentEvent?.id) {
                    updateMeshPresence(presence.peerId)
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedEventControl.collect { control ->
                val current = sessionManager.sessionState.value.currentEvent ?: return@collect
                if (sessionManager.sessionState.value.isHost || control.eventCode != current.id) return@collect
                if (current.hostPeerId != null && control.hostPeerId != current.hostPeerId) return@collect
                if (control.isClosed) {
                    stopDiscovery()
                    sessionManager.endEventFromHost()
                    return@collect
                }
                val updated = current.copy(
                    useUwb = control.useUwb,
                    useGnss = control.useGnss,
                    useWifiDirect = control.useWifiDirect,
                    acceptsNewMembers = control.acceptsNewMembers,
                    hostPeerId = control.hostPeerId,
                )
                if (updated != current) {
                    sessionManager.updateEvent(updated)
                    activeDiscoveryUseUwb = updated.useUwb
                    activeDiscoveryUseWifiDirect = updated.useWifiDirect
                    if (current.useUwb != updated.useUwb) {
                        uwbSessionJob?.cancel()
                        activeRangingJobs.values.forEach { it.cancel() }
                        activeRangingJobs.clear()
                        startUwbRangingSession()
                    }
                    onFocusChanged()
                }
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatMessage.collect { dto ->
                updatePeerName(dto.senderPeerId, dto.senderName)
                val chatMessage = ChatMessage(
                    id = dto.id,
                    senderPeerId = dto.senderPeerId,
                    senderName = dto.senderName,
                    messageText = dto.text,
                    timestamp = dto.timestamp,
                    colorArgb = dto.colorArgb,
                )
                sessionManager.receiveLocalChatMessage(chatMessage)

                if (dto.senderPeerId != sessionManager.peerId.value) {
                    val appWideEnabled = appearanceSettings.chatNotificationsEnabled.first()
                    val eventEnabled = sessionManager.sessionState.value.currentEvent?.chatNotificationsEnabled ?: true
                    if (appWideEnabled && eventEnabled) {
                        chatNotifier.onChatMessageReceived(chatMessage)
                    }
                }
            }
        }

        // Register newly connected peers and deliver the host's current chat history.
        repositoryScope?.launch {
            nearbyConnectionsManager.connectionEstablished.collect { peerId ->
                val state = sessionManager.sessionState.value
                if (!state.isHost && state.currentEvent?.hostPeerId == peerId) {
                    _hostConnectionLostAtMillis.value = null
                }
                addConnectedPeer(peerId)
                // A peer with a live connection is the only kind that can be
                // UWB-ranged, so this is what makes it eligible as a focus.
                connectedPeerIds.add(peerId)
                // The peer is back on the transport, so any retention held for
                // it has served its purpose. Clearing it here means the normal
                // connected path takes over and the member is no longer treated
                // as a special out-of-range case.
                if (retainedRangingPeerIds.remove(peerId) != null) {
                    Log.i(TAG, "Peer $peerId is back in transport range; releasing its retained UWB session")
                }
                onFocusChanged()
                if (sessionManager.sessionState.value.isHost) {
                    sessionManager.sessionState.value.currentEvent?.let { event ->
                        event.inviteSecret?.let { secret ->
                            nearbyConnectionsManager.sendInviteSecret(
                                peerId,
                                InviteSecretPayloadDto(event.id, secret, sessionManager.peerId.value),
                            )
                        }
                        localBleIdentityKey?.let { identityKey ->
                            nearbyConnectionsManager.sendBleIdentityKey(peerId, identityKey)
                        }
                        bleIdentityKeys.values
                            .filter { it.peerId != sessionManager.peerId.value && it.peerId != peerId }
                            .forEach { identityKey -> nearbyConnectionsManager.sendBleIdentityKey(peerId, identityKey) }
                        nearbyConnectionsManager.sendEventControl(
                            peerId,
                            EventControlPayloadDto(
                                eventCode = event.id,
                                hostPeerId = sessionManager.peerId.value,
                                useUwb = event.useUwb,
                                useGnss = event.useGnss,
                                useWifiDirect = event.useWifiDirect,
                                acceptsNewMembers = event.acceptsNewMembers,
                                isFull = sessionManager.sessionState.value.friends.size + 1 >= MAX_PARTICIPANTS,
                            ),
                        )
                    }
                    val history = sessionManager.sessionState.value.chatMessages.map { message ->
                        ChatPayloadDto(
                            id = message.id,
                            senderPeerId = message.senderPeerId,
                            senderName = message.senderName,
                            text = message.messageText,
                            timestamp = message.timestamp,
                            colorArgb = message.colorArgb,
                        )
                    }
                    nearbyConnectionsManager.sendChatHistoryToPeer(peerId, history)
                }
            }
        }

        // A relayed ranging offer makes this device the controlee for a
        // guest-to-guest session. The host consumes offers rather than emitting
        // them (see NearbyConnectionsManager.dispatchEnvelope), so a host
        // simply sees nothing here.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedUwbRangingOffer.collect { offer ->
                acceptIncomingRangingOffer(offer)
            }
        }

        // A peer whose transport connection dropped may still be reachable over
        // UWB, which reaches further than the transport does. Rather than
        // forgetting it outright, keep the ranging session alive for the peer
        // actually being tracked so that walking back into range is instant
        // instead of renegotiating a channel and STS key.
        repositoryScope?.launch {
            nearbyConnectionsManager.peerDisconnected.collect { peerId ->
                val state = sessionManager.sessionState.value
                val event = state.currentEvent
                val meshPresenceFresh = _peerUpdates.value[peerId]?.meshPresenceLastSeenAtMillis
                    ?.let { System.currentTimeMillis() - it < PEER_EXPIRY_TIMEOUT_MILLIS } == true
                if (!state.isHost && event?.hostPeerId == peerId && !meshPresenceFresh) {
                    _hostConnectionLostAtMillis.value = System.currentTimeMillis()
                }
                if (meshPresenceFresh) {
                    connectedPeerIds.add(peerId)
                    return@collect
                }
                val retained = UwbRetentionPolicy.retainOnDisconnect(
                    peerId = peerId,
                    rangedTargetId = activeFocusRangingTarget,
                    knownUwbAddress = peerUwbAddresses[peerId],
                    ringIsActive = event != null,
                    ringIsOver = event == null,
                )
                if (retained != null) {
                    // Keep the address and the pin: only the transport link is
                    // gone, not the member. The retention sweep will drop this
                    // if the window lapses or the ring ends first.
                    retainedRangingPeerIds[peerId] = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "Peer $peerId dropped transport but is UWB-ranged; retaining the " +
                            "session for ${UwbRetentionPolicy.RETENTION_MILLIS}ms so reconnection is instant"
                    )
                    // The focus must not move: moving it would spend the single
                    // UWB session on someone else and abandon the peer we are
                    // deliberately holding open.
                    return@collect
                }

                Log.i(TAG, "Peer $peerId disconnected; removing it from the radar")
                connectedPeerIds.remove(peerId)
                peerUwbAddresses.remove(peerId)
                removePeer(peerId)
                // If that was the member being tracked, the focus has to be
                // re-resolved or the UWB session would sit on a dead peer.
                onFocusChanged()
            }
        }

        // Drop a retained session once its window lapses. Without this a peer
        // that walked away would stay on the radar indefinitely, holding the one
        // UWB session open so nothing else could ever be ranged.
        repositoryScope?.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                // The window comes from the power policy rather than the
                // constant, so a phone already running two other radios gives
                // up the UWB session sooner. UwbRetentionPolicy still owns
                // whether retaining is right at all; this only decides how long
                // it is worth paying for.
                val window = currentRadioPlan(
                    BleRangePolicy.supportsExtendedAdvertising(context)
                ).uwbRetentionMillis
                val expired = retainedRangingPeerIds
                    .filterValues { !UwbRetentionPolicy.isWithinRetentionWindow(it, now, window) }
                    .keys
                if (expired.isNotEmpty()) {
                    Log.i(TAG, "Retention window lapsed for $expired; releasing their UWB sessions")
                    expired.forEach { peerId ->
                        retainedRangingPeerIds.remove(peerId)
                        connectedPeerIds.remove(peerId)
                        peerUwbAddresses.remove(peerId)
                        removePeer(peerId)
                    }
                    onFocusChanged()
                }
            }
        }

        // Debug-only: scripted ranges from tools/mock_radar.py, forwarded by the
        // LAN debug relay. The emulator has no UWB or BLE radio, so without
        // these the radar would have no distance to draw and the only thing
        // testable between two devices would be chat and beacons.
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedMockRanging.collect { dto ->
                updateMockRanging(dto)
            }
        }

        // The LAN debug relay reports event closure here. Nearby peers receive
        // authenticated EventControl on the separate receivedEventControl flow.
        // Either way, leaving clears the session and returns navigation Home.
        repositoryScope?.launch {
            nearbyConnectionsManager.eventClosed.collect {
                Log.i(TAG, "Host ended the PingRing; leaving the event")
                sessionManager.endEventFromHost()
            }
        }

        repositoryScope?.launch {
            nearbyConnectionsManager.receivedChatHistory.collect { history ->
                history.messages.forEach { dto ->
                    updatePeerName(dto.senderPeerId, dto.senderName)
                    sessionManager.receiveLocalChatMessage(
                        ChatMessage(
                            id = dto.id,
                            senderPeerId = dto.senderPeerId,
                            senderName = dto.senderName,
                            messageText = dto.text,
                            timestamp = dto.timestamp
                        )
                    )
                }
            }
        }

        // 2c. Start Beacon Processing Queue Flow Listener Loop
        repositoryScope?.launch {
            nearbyConnectionsManager.receivedBeacon.collect { dto ->
                val localPeerId = sessionManager.peerId.value
                // If message is from ourselves, ignore
                if (dto.senderPeerId == localPeerId) return@collect

                val info = BeaconInfo(
                    id = dto.id,
                    senderPeerId = dto.senderPeerId,
                    senderName = dto.senderName,
                    title = dto.title,
                    colorArgb = dto.colorArgb,
                    vibrationPattern = dto.vibrationPattern,
                    isActive = dto.isActive,
                    timestamp = dto.timestamp
                )

                if (dto.isActive) {
                    updatePeerName(dto.senderPeerId, dto.senderName)
                    _activeBeacons.update { it + (dto.senderPeerId to info) }
                    // The recipient's alert honours THIS device's repeat setting, not
                    // anything the sender chose - see BeaconAlertRepeat - and is
                    // skipped entirely if the member silenced this beacon.
                    val patternEnum = BeaconVibrationPattern.fromName(dto.vibrationPattern)
                    playBeaconAlert(dto.id, patternEnum.pattern)
                } else {
                    endBeacon(dto.senderPeerId)
                }

            }
        }

        // 3. Start BLE Advertising & Scanning
        startSignedBleAdvertising()
        startBleScanning()

        // 3. Start Sensor Fusion
        orientationTracker.resume()

        // 4. Start foreground-only UWB ranging
        startUwbRangingSession()
    }

    fun stopDiscovery() {
        gnssRanging.setEnabled(false)
        wifiRttRanging.stop()
        peerGnssFixes.clear()
        additionalRangingSequences.clear()
        localRangingSequence.set(0L)
        _hostConnectionLostAtMillis.value = null
        activeDiscoveryEventId = null
        activeDiscoveryPeerId = null
        activeDiscoveryInviteSecret = null
        activeDiscoveryIsHost = null
        activeDiscoveryUseUwb = null
        activeDiscoveryUseWifiDirect = null
        activeDiscoveryUseHotspot = null
        stopVibration()
        _activeBeacons.value = emptyMap()
        // The ended-beacon history belongs to the session that produced it. Left
        // in place it would greet the next PingRing with beacons raised in the
        // last one, which is the same class of bug as replaying another event's
        // chat history.
        _endedBeacons.value = emptyList()
        _localBeacon.value = null
        nearbyConnectionsManager.stopAll()
        stopBleAdvertising()
        stopBleScanning()
        orientationTracker.pause()
        uwbSessionJob?.cancel()
        uwbSessionJob = null
        activeRangingJobs.values.forEach { it.cancel() }
        activeRangingJobs.clear()
        // The focus is per-session state: peer ids are ephemeral, so carrying a
        // selection into the next event would point the radar at nobody.
        activeFocusRangingJob?.cancel()
        activeFocusRangingJob = null
        activeFocusRangingTarget = null
        connectedPeerIds.clear()
        departedPeerIds.clear()
        peerUwbAddresses.clear()
        localBleIdentityKeyPair = null
        localBleIdentityKey = null
        bleIdentityKeys.clear()
        latestBleAdvertisementSequences.clear()
        nextBleAdvertisementSequence.set(0L)
        bleSignalWarningTracker.clear()
        _lastKnownPeerDirections.value = emptyList()
        // Every retained address goes too, and this is the only place that does
        // it. Leaving, or the host ending the ring, must leave nothing rangeable
        // behind: a member that left is not still a member, and a session that
        // outlived the ring would keep ranging someone who no longer has any
        // right to it.
        retainedRangingPeerIds.clear()
        lastUwbMeasurementAt.clear()
        // Numbering restarts for the next event; no membership is carried over.
        peerDisplayNames.clear()
        _focusedPeerId.value = null
        repositoryScope?.cancel()
        repositoryScope = null
        _peerUpdates.value = emptyMap()
    }

    fun broadcastOutgoingChatMessage(text: String, senderName: String, colorArgb: Long?) {
        // Sanitized again here, not just at the field, because a sender name also
        // arrives from a peer: this is the only place every outgoing message is
        // checked regardless of which screen sent it.
        //
        // The previous `take(MAX_CHAT_LENGTH)` could cut a surrogate pair in
        // half, because a Char is a UTF-16 unit and an emoji is two of them -
        // leaving a lone surrogate to be serialised and sent to every peer.
        val sanitizedText = TextInputPolicy.sanitizeMessage(text)
        val sanitizedName = TextInputPolicy.sanitizeUsername(senderName)
        if (sanitizedText.isBlank() || sanitizedName.isBlank()) return
        val localPeerId = sessionManager.peerId.value
        val dto = ChatPayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = sanitizedName,
            text = sanitizedText,
            timestamp = System.currentTimeMillis(),
            colorArgb = colorArgb,
        )
        
        // Append locally immediately
        sessionManager.receiveLocalChatMessage(
            ChatMessage(
                id = dto.id,
                senderPeerId = dto.senderPeerId,
                senderName = dto.senderName,
                messageText = dto.text,
                timestamp = dto.timestamp,
                colorArgb = dto.colorArgb,
            )
        )

        // Broadcast to P2P mesh endpoints
        nearbyConnectionsManager.broadcastChatMessage(dto)
    }

    /**
     * Play [pattern] as a beacon alert under this device's alert settings.
     *
     * The settings are read from the flow's current value rather than passed in
     * at each call site, because the two places a beacon is played - the sender's
     * own phone and every recipient's - must not be able to disagree about it.
     *
     * A null result means the pattern asked for silence, and also cancels
     * anything already looping: a member who switches to "No Vibration" should
     * not be left with a buzz that started before they changed their mind.
     */
    private var beaconStopJob: Job? = null

    fun playBeaconAlert(beaconId: String, pattern: LongArray) {
        if (!BeaconAlertPolicy.shouldAlert(beaconId, _silencedBeaconIds.value, pattern)) {
            // A member who silences a repeating beacon must get silence now, not
            // after the current buzz loop finishes.
            stopVibration()
            return
        }
        val settings = _beaconAlertSettings.value
        val wave = BeaconAlertPolicy.waveformFor(pattern, settings)
        if (wave == null) {
            stopVibration()
            return
        }
        try {
            val effect = VibrationEffect.createWaveform(wave.timings, wave.repeatFromIndex)
            vibrator?.vibrate(effect)

            beaconStopJob?.cancel()
            if (settings.repeat == BeaconAlertRepeat.REPEAT && settings.repeatDurationMillis != BEACON_REPEAT_DURATION_UNTIL_STOPPED) {
                beaconStopJob = controlScope.launch {
                    delay(settings.repeatDurationMillis)
                    stopVibration()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Beacon alert playback failed", e)
        }
    }

    /**
     * Stop, or resume, alerting for one beacon.
     *
     * Silencing cancels anything currently sounding for that beacon, which is
     * the whole point: the member is acting because their phone is buzzing now.
     *
     * The beacon itself is untouched. It stays in the transcript, stays on the
     * radar and stays active, because muting an alert must never become a way
     * of not being told something. Nothing here is sent to the sender or to
     * anybody else.
     */
    fun setBeaconSilenced(beaconId: String, silenced: Boolean) {
        val current = _silencedBeaconIds.value
        _silencedBeaconIds.value =
            if (silenced) current + beaconId else current - beaconId
        appearanceSettings.setBeaconSilenced(beaconId, silenced)

        if (silenced) {
            // Only the silenced beacon's own loop is stopped. A repeating alert
            // belongs to one beacon at a time on this device, so cancelling is
            // safe here and is what the member asked for.
            stopVibration()
        } else {
            _activeBeacons.value.values.firstOrNull { it.id == beaconId }?.let { beacon ->
                playBeaconAlert(beacon.id, BeaconVibrationPattern.fromName(beacon.vibrationPattern).pattern)
            }
        }
    }

    /**
     * Change how this device alerts for beacons, and persist it.
     *
     * Also re-evaluates whatever is currently sounding: a member who turns
     * Repeat on mid-beacon should start repeating, and one who turns it off
     * should get silence immediately rather than waiting for the buzz to finish
     * on its own.
     */
    fun setBeaconAlertSettings(settings: BeaconAlertSettings) {
        val normalized = settings.normalized()
        _beaconAlertSettings.value = normalized
        appearanceSettings.setBeaconAlertSettings(normalized)

        // Silence is immediate. Not cancelling here would leave a looping alert
        // running under the settings that were just replaced.
        if (normalized.repeat == BeaconAlertRepeat.ONCE) {
            stopVibration()
        } else {
            _activeBeacons.value.values.firstOrNull()?.let { active ->
                playBeaconAlert(active.id, BeaconVibrationPattern.fromName(active.vibrationPattern).pattern)
            }
        }
    }

    /** Kept for the Test Vibration button, which must not obey the repeat setting. */
    fun playVibrationPattern(pattern: LongArray) {
        if (pattern.isEmpty() || (pattern.size == 1 && pattern[0] == 0L)) return
        try {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            vibrator?.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration playback failed", e)
        }
    }

    fun stopVibration() {
        beaconStopJob?.cancel()
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel vibration", e)
        }
    }

    fun activateBeacon(
        title: String,
        colorArgb: Long,
        vibrationPattern: String,
        senderName: String
    ) {
        val localPeerId = sessionManager.peerId.value
        val beaconDto = BeaconPayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = senderName,
            title = title,
            colorArgb = colorArgb,
            vibrationPattern = vibrationPattern,
            isActive = true,
            timestamp = System.currentTimeMillis()
        )
        val info = BeaconInfo(
            id = beaconDto.id,
            senderPeerId = beaconDto.senderPeerId,
            senderName = beaconDto.senderName,
            title = beaconDto.title,
            colorArgb = beaconDto.colorArgb,
            vibrationPattern = beaconDto.vibrationPattern,
            isActive = true,
            timestamp = beaconDto.timestamp
        )
        _localBeacon.value = info
        _activeBeacons.update { it + (localPeerId to info) }

        // Vibrate on the device that raised the beacon too, not only on the
        // ones that receive it. Recipients already play the pattern when the
        // broadcast arrives, but the sender was getting silence from their own
        // beacon - which reads as the feature being broken on the one phone the
        // user is holding, and is why people reached for the Test Vibration
        // button to check whether the radio worked at all.
        playBeaconAlert(beaconDto.id, BeaconVibrationPattern.fromName(vibrationPattern).pattern)

        nearbyConnectionsManager.broadcastBeacon(beaconDto)
    }

    fun cancelLocalBeacon() {
        val current = _localBeacon.value ?: return
        val beaconDto = BeaconPayloadDto(
            id = current.id,
            senderPeerId = current.senderPeerId,
            senderName = current.senderName,
            title = current.title,
            colorArgb = current.colorArgb,
            vibrationPattern = current.vibrationPattern,
            isActive = false,
            timestamp = System.currentTimeMillis()
        )
        _localBeacon.value = null
        endBeacon(current.senderPeerId)
        stopVibration()

        nearbyConnectionsManager.broadcastBeacon(beaconDto)
    }

    @SuppressLint("MissingPermission")
    private fun startSignedBleAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        if (!BleRangePolicy.supportsSignedBleIdentityAdvertising(context)) {
            _connectivityStatus.value = getConnectivityStatus()
            Log.w(TAG, "BLE identity proximity unavailable: extended advertising is unsupported")
            return
        }
        val event = sessionManager.sessionState.value.currentEvent ?: return
        val keyPair = localBleIdentityKeyPair ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerId = sessionManager.peerId.value
        val isHost = sessionManager.sessionState.value.isHost
        // Only the host bit survives here. The advert's job is to identify a member of
        // this ring to the other members already in it; it no longer advertises
        // the ring as something a stranger can find and join.
        val flags = if (isHost) BleIdentityAdvertisement.FLAG_HOST else 0
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        fun advertisingData(sequence: Long): AdvertiseData? = runCatching {
            val payload = BleIdentityAdvertisement.encode(
                flags = flags,
                peerId = peerId,
                eventCode = event.id,
                joinedAtMillis = sessionManager.sessionState.value.joinedAtMillis ?: 0L,
                sequence = sequence,
                privateKey = keyPair.private,
            )
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
                .addServiceData(ParcelUuid(PINGRING_SERVICE_UUID), payload)
                .build()
        }.onFailure { Log.e(TAG, "Could not build signed BLE identity advertisement", it) }.getOrNull()

        val initialData = advertisingData(nextBleAdvertisementSequence.getAndIncrement()) ?: return
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                synchronized(this@DiscoveryRepository) {
                    if (signedAdvertisingCallback !== this || status != AdvertisingSetCallback.ADVERTISE_SUCCESS || set == null) {
                        advertiser.stopAdvertisingSet(this)
                        Log.e(TAG, "Signed extended BLE advertising failed to start: status=$status")
                        return
                    }
                    signedAdvertisingSet = set
                    signedAdvertisementRefreshJob?.cancel()
                    signedAdvertisementRefreshJob = controlScope.launch {
                        while (isActive && signedAdvertisingSet === set) {
                            delay(1_000L)
                            val data = advertisingData(nextBleAdvertisementSequence.getAndIncrement()) ?: break
                            runCatching { set.setAdvertisingData(data) }
                                .onFailure { Log.w(TAG, "Could not refresh signed BLE advertisement", it) }
                        }
                    }
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                synchronized(this@DiscoveryRepository) {
                    if (signedAdvertisingCallback === this) {
                        signedAdvertisementRefreshJob?.cancel()
                        signedAdvertisementRefreshJob = null
                        signedAdvertisingSet = null
                        signedAdvertisingCallback = null
                        signedBleAdvertiser = null
                    }
                }
            }
        }
        signedBleAdvertiser = advertiser
        signedAdvertisingCallback = callback
        try {
            advertiser.startAdvertisingSet(parameters, initialData, null, null, null, callback)
        } catch (error: Exception) {
            if (signedAdvertisingCallback === callback) {
                signedAdvertisingCallback = null
                signedBleAdvertiser = null
            }
            Log.e(TAG, "Exception starting signed extended BLE advertising", error)
        }
    }

    @Synchronized
    private fun startBleAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val currentPeerId = sessionManager.peerId.value
        val currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        if (currentEvent.inviteSecret == null) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val peerIdBytes = currentPeerId.toByteArray(StandardCharsets.UTF_8).copyOf(8)
        val currentMemberCount = sessionManager.sessionState.value.friends.size + 1
        val isFull = currentMemberCount >= MAX_PARTICIPANTS
        // BLE flags are untrusted discovery hints. Host role, closure, policy,
        // capacity and admission settings are sent over authenticated Nearby, and
        // no flag here offers the session to a phone that was not already told
        // about it, so this advert carries no policy flags at all.
        val flags = 0
        val servicePayload = buildServicePayload(flags, peerIdBytes)

        val settings = BleRangePolicy.advertiseSettings()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .addServiceData(ParcelUuid(PINGRING_SERVICE_UUID), servicePayload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                synchronized(this@DiscoveryRepository) {
                    if (advertiseCallback === this) {
                        advertisingActive = true
                        Log.d(TAG, "BLE Advertising started successfully")
                    } else {
                        advertiser.stopAdvertising(this)
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(this@DiscoveryRepository) {
                    if (advertiseCallback === this) {
                        advertiseCallback = null
                        bleAdvertiser = null
                        advertisingActive = false
                    }
                }
                Log.e(TAG, "BLE Advertising failed to start: $errorCode")
            }
        }
        bleAdvertiser = advertiser
        advertiseCallback = callback
        advertisingActive = false
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: Exception) {
            if (advertiseCallback === callback) {
                advertiseCallback = null
                bleAdvertiser = null
                advertisingActive = false
            }
            Log.e(TAG, "Exception starting BLE advertising", e)
        }
    }

    @Synchronized
    private fun stopBleAdvertising() {
        stopSignedBleAdvertising()
        val advertiser = bleAdvertiser
        val callback = advertiseCallback
        bleAdvertiser = null
        advertiseCallback = null
        val shouldStop = advertisingActive
        advertisingActive = false
        if (advertiser != null && callback != null && shouldStop) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping BLE advertising", e)
            }
        }
    }

    @Synchronized
    private fun stopSignedBleAdvertising() {
        val advertiser = signedBleAdvertiser
        val callback = signedAdvertisingCallback
        signedBleAdvertiser = null
        signedAdvertisingSet = null
        signedAdvertisingCallback = null
        signedAdvertisementRefreshJob?.cancel()
        signedAdvertisementRefreshJob = null
        if (advertiser != null && callback != null) {
            runCatching { advertiser.stopAdvertisingSet(callback) }
                .onFailure { Log.e(TAG, "Exception stopping signed extended BLE advertising", it) }
        }
    }

    /**
     * Sends a terminal host packet before the host clears its local session. Nearby members
     * receiving this packet clear their session and navigation returns them to Home.
     *
     * The LAN debug transport has no BLE radio to advertise with (the emulator
     * has none at all), so it is told through [NearbyTransport.announceEventClosed]
     * instead, which makes the relay close the event for its connected members.
     */
    fun announceEventClosure(event: Event) {
        nearbyConnectionsManager.announceEventClosed()
        broadcastEventControl(event, isClosed = true)
    }

    fun broadcastEventControl(event: Event, isClosed: Boolean = false) {
        if (!sessionManager.sessionState.value.isHost) return
        nearbyConnectionsManager.acceptsNewMembers = event.acceptsNewMembers
        val control = EventControlPayloadDto(
            eventCode = event.id,
            hostPeerId = sessionManager.peerId.value,
            useUwb = event.useUwb,
            useGnss = event.useGnss,
            useWifiDirect = event.useWifiDirect,
            acceptsNewMembers = event.acceptsNewMembers,
            isFull = sessionManager.sessionState.value.friends.size + 1 >= MAX_PARTICIPANTS,
            isClosed = isClosed,
        )
        connectedPeerIds.forEach { peerId -> nearbyConnectionsManager.sendEventControl(peerId, control) }
    }

    /**
     * Builds the service payload for the current session.
     *
     * The event code is supplied here rather than by each caller so that no
     * advert can go out without it: a member that cannot be told which ring it
     * is in cannot be identified as a member of it by the peers scanning.
     */
    private fun buildServicePayload(flags: Int, peerIdBytes: ByteArray): ByteArray {
        val event = sessionManager.sessionState.value.currentEvent ?: return ByteArray(0)
        val secret = event.inviteSecret ?: return ByteArray(0)
        return BleAdvertisementPayload.encode(
            flags = flags,
            peerIdBytes = peerIdBytes,
            eventCode = event.id,
            // Advertised so a host can answer "who arrived first" from what we
            // say rather than from when it happened to hear us. Zero when this
            // device is not in an event, which decodes as "unknown" rather than
            // as the earliest possible arrival.
            joinedAtMillis = sessionManager.sessionState.value.joinedAtMillis ?: 0L,
                inviteSecret = secret,
        )
            }

    private companion object {
        const val PEER_CLEANUP_INTERVAL_MILLIS = 5_000L
        const val PEER_EXPIRY_TIMEOUT_MILLIS = 15_000L
        const val UWB_DIRECTION_STALE_MILLIS = 2_000L

        /**
         * Debounce before moving the UWB session to a newly focused member.
         * Switching costs a handshake, so flicking through the member list must
         * not open and abandon a session per tap.
         */
        const val UWB_FOCUS_SETTLE_MILLIS = 750L
    }

    /**
     * Scan callbacks, one per pass. Android delivers every matching advert to
     * each registered callback, so the callbacks deliberately share one
     * [scanCallback] implementation and differ only in the settings they were
     * registered with.
     */
    private val scanCallbacks = mutableListOf<ScanCallback>()

    private fun startBleScanning() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PINGRING_SERVICE_UUID))
            .build()

        val extended = BleRangePolicy.supportsExtendedAdvertising(context)
        // The pass count and scan mode come from the power policy rather than
        // from BleRangePolicy's fixed answer, so a ring that does not need two
        // passes is not paying for them. BleRangePolicy still owns what each
        // pass looks like; this decides how many there are and how hard.
        val plan = currentRadioPlan(extended)
        BleRangePolicy.logSummary(context)
        Log.i(TAG, "Radio plan: ${RadioPowerPolicy.describe(plan)}")

        // Each pass gets its own ScanCallback instance: Android treats the
        // callback identity as the scan request, so reusing one would replace
        // the previous pass rather than add to it.
        repeat(plan.scanPassCount) { pass ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { handleScanResult(it) }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { handleScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan pass $pass failed: $errorCode")
                }
            }
            val settings = BleRangePolicy.scanSettingsForPass(pass, extended, plan.scanEffort)
            try {
                bleScanner?.startScan(listOf(filter), settings, callback)
                scanCallbacks.add(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting BLE scan pass $pass", e)
            }
        }
    }

    /**
     * The radio settings for what this device is doing right now.
     *
     * Read fresh each time rather than cached, because every input - member
     * count, whether UWB is ranging, whether the phone is on mains - can change
     * mid-ring. Recomputing is a handful of comparisons; getting it stale would
     * mean a phone that filled up after scanning started never escalating.
     *
     * [hotspotActive] is always false: a host-run hotspot turned out not to be
     * implementable by a third-party app (see [ConnectionMethodPolicy]), so
     * there is no such radio for this to account for. It is kept as an input so
     * the day one exists, the policy already reads it.
     */
    private fun currentRadioPlan(supportsExtended: Boolean): RadioPowerPolicy.Plan {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent
        val selection = ConnectionMethodPolicy.chooseMethod(
            capabilities = ConnectionMethodPolicy.Capabilities(
                apiLevel = Build.VERSION.SDK_INT,
                uwbHardwareSupported = isUwbSupported(),
                uwbPermissionGranted = event?.useUwb ?: false,
            ),
            preferences = ConnectionMethodPolicy.Preferences(
                uwbRangingEnabled = event?.useUwb ?: false,
            ),
        )
        return RadioPowerPolicy.plan(
            load = RadioPowerPolicy.Load(
                isHost = state.isHost,
                memberCount = _peerUpdates.value.size,
                uwbActive = selection.usesUwb,
                hotspotActive = false,
                onBattery = isOnBattery(),
            ),
            supportsExtendedAdvertising = supportsExtended,
        )
    }

    /**
     * Whether the device is on battery rather than plugged in.
     *
     * `BATTERY_PLUGGED` of none means either unplugged *or* on battery, and the
     * alternative - reading the capacity percentage - is worse: it changes for
     * reasons that have nothing to do with the charger. Treated as unknown and
     * assumed to be on battery, because the settings this gates are the ones
     * worth having on a phone someone is going to forget to charge at a
     * festival.
     */
    private fun isOnBattery(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged == 0
    }

    private fun stopBleScanning() {
        scanCallbacks.forEach { callback ->
            try {
                bleScanner?.stopScan(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping a BLE scan pass", e)
            }
        }
        scanCallbacks.clear()
        bleScanner = null
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(PINGRING_SERVICE_UUID)) ?: return
        // Too short to be one of ours: another app's service UUID, or a
        // truncated read. Decoding it would put a phantom member on the radar.
        if (serviceData.size != BleIdentityAdvertisement.TOTAL_BYTES) return

        var currentEvent = sessionManager.sessionState.value.currentEvent
        if (currentEvent == null) {
            // Not in a ring: nothing to do with this advert. There is no browse
            // list any more - joining is by code or by scanning the host's QR
            // code - so a nearby host is only interesting once this device has
            // been told which ring it is joining.
            return
        }
        val hint = BleIdentityAdvertisement.decodeDiscoveryHint(serviceData)
        if (!sessionManager.sessionState.value.isHost && currentEvent.hostPeerId == null &&
            hint?.isHost == true && hint.eventCode == currentEvent.id
        ) {
            currentEvent = currentEvent.copy(hostPeerId = hint.peerId)
            sessionManager.updateEvent(currentEvent)
            (nearbyConnectionsManager as? NearbyConnectionsManager)?.connectToPeer(hint.peerId)
        }
        if (!getConnectivityStatus().supportsBleIdentity) return
        val peerId = BleIdentityAdvertisement.readPeerId(serviceData) ?: return
        val keyPayload = bleIdentityKeys[peerId] ?: return
        val publicKey = BleIdentityCrypto.decodePublicKey(keyPayload.publicKey) ?: return
        val decoded = BleIdentityAdvertisement.decode(serviceData, publicKey) ?: return
        if (decoded.eventCode != currentEvent.id) return
        if (decoded.peerId != peerId) return
        if (peerId == sessionManager.peerId.value) return
        if ((nearbyConnectionsManager as? NearbyConnectionsManager)?.hasLiveKeyControl(peerId) != true) return
        var acceptedSequence = false
        latestBleAdvertisementSequences.compute(peerId) { _, previous ->
            if (BleIdentityAdvertisement.acceptsSequence(previous, decoded.sequence)) {
                acceptedSequence = true
                decoded.sequence
            } else {
                previous
            }
        }
        if (!acceptedSequence) return
        if (peerId.isEmpty() || peerId == sessionManager.peerId.value) return

        val rssi = result.rssi
        val proximity = if (rssi >= -70) "Hot" else "Cold"

        // Find or create existing update
        val existing = _peerUpdates.value[peerId]
        // Never fall back to the peer id: it is an internal routing key, not
        // something a person can read or act on.
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }

        // A UWB measurement is real ranging, so it always wins over an RSSI
        // guess. Only peers that have never been ranged fall back to the
        // estimate, which is a rolling average over the last couple of seconds
        // of adverts - the curve is applied to the mean, not to each reading,
        // so one reflected packet cannot move the readout.
        val nowMillis = System.currentTimeMillis()
        val rssiSmoother = rssiSmoothers.getOrPut(peerId) { RssiSmoother() }
        val rssiEstimate = rssiSmoother.add(rssi, nowMillis)
        val signalWeak = bleSignalWarningTracker.update(
            peerId = peerId,
            smoothedRssiDbm = rssiSmoother.smoothedRssi(nowMillis),
            nowMillis = nowMillis,
        )
        val hasUwbFix = existing?.distanceSource == RangingSourcePolicy.RangingSource.UWB &&
            uwbRangedPeers.contains(peerId)
        val hasAdditionalFix = existing?.lastAdditionalRangingAtMillis?.let {
            nowMillis - it in 0..additionalDistanceLifetime(existing.distanceSource)
        } == true

        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = rssi,
            proximity = proximity,
            distance = if (hasUwbFix || hasAdditionalFix) existing?.distance else rssiEstimate ?: existing?.distance,
            azimuth = existing?.azimuth,
            elevation = existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = nowMillis,
            signalWeak = signalWeak,
            meshPresenceLastSeenAtMillis = existing?.meshPresenceLastSeenAtMillis,
            distanceSource = if (hasUwbFix || hasAdditionalFix) existing?.distanceSource else RangingSourcePolicy.RangingSource.BLE_RSSI,
            distanceAccuracyMeters = existing?.distanceAccuracyMeters.takeIf { hasUwbFix || hasAdditionalFix },
            lastAdditionalRangingAtMillis = existing?.lastAdditionalRangingAtMillis.takeIf { hasAdditionalFix },
            // Cross-checked only once a UWB fix exists to check against. Before
            // that there is a single source and the answer is UNKNOWN, which the
            // radar shows as an estimate rather than as a confirmed distance.
            rangingConfidence = if (hasUwbFix) {
                RangingSourcePolicy.confidence(rssi, rssiEstimate)
            } else {
                RangingConfidence.UNKNOWN
            },
            // From the advert, and sticky: a packet read without one must not
            // erase a timestamp an earlier advert carried, or a peer's place in
            // the arrival order would reset whenever one of its adverts was
            // short. Zero means "did not say", which is null here rather than
            // epoch 1970 - see RadarPeerUpdate.joinedAtMillis.
            joinedAtMillis = decoded.joinedAtMillis.takeIf { it > 0L } ?: existing?.joinedAtMillis
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }

        syncFriend(updated)
    }

    /**
     * Mirrors the latest radar reading of [peer] into the SessionManager's
     * friend list: added on first sight, then only its last-seen time and
     * distance are refreshed.
     */
    private fun syncFriend(peer: RadarPeerUpdate) {
        val friends = sessionManager.sessionState.value.friends
        val distance = peer.distance?.toDouble()
        val friendExists = friends.any { it.peerId == peer.peerId }
        if (!friendExists) {
            val updatedFriends = friends + Friend(
                peerId = peer.peerId,
                name = peer.name,
                lastSeen = peer.lastSeen,
                distance = distance
            )
            sessionManager.updateFriends(updatedFriends)
        } else {
            val updatedFriends = friends.map {
                if (it.peerId == peer.peerId) it.copy(lastSeen = peer.lastSeen, distance = distance) else it
            }
            sessionManager.updateFriends(updatedFriends)
        }
    }

    /**
 * Forgets a peer's *live* state: its radar pin, any beacon it was broadcasting,
 * and the ranging and smoothing state that only makes sense while it is in reach.
 *
 * Deliberately does NOT remove it from [sessionManager]'s friends. Losing contact
 * is not leaving: a member who walks to the other side of a room is still in the
 * ring, and the member list is membership rather than reachability. Removing them
 * there made the member list disagree with ring membership, and each device
 * dropped a different member - whichever one's transport dropped first - so the
 * two phones each showed the other as gone while both were still counted in the
 * ring.
 *
 * A member leaves the list only on an authenticated departure: an explicit
 * graceful departure, or the host ending the event. Both clear the whole session,
 * so nothing here has to unpick a half-removed member.
 */
private fun removePeer(peerId: String, preserveLastKnownDirection: Boolean = true) {
        if (preserveLastKnownDirection) rememberLastKnownPeerDirection(peerId)
        else _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        _peerUpdates.update { current -> current - peerId }
        // A peer that walks away mid-beacon has its beacon ended rather than
        // dropped: it happened, and the transcript should still show where.
        endBeacon(peerId)
        // A peer that reconnects must be re-ranged from scratch: keeping the
        // old flags would suppress its RSSI estimate, and keeping the old
        // average would seed it with a distance from a session that is over.
        uwbRangedPeers.remove(peerId)
        rssiSmoothers.remove(peerId)
        bleSignalWarningTracker.remove(peerId)
        lastUwbMeasurementAt.remove(peerId)
        retainedRangingPeerIds.remove(peerId)
        peerUwbAddresses.remove(peerId)
        // The display label is keyed to the member rather than to their
        // proximity, so it survives a drop. Forgetting it renumbered a member who
        // had merely stepped out of range, which is how two people ended up shown
        // under different numbers depending on who had dropped out of contact.
        // Nothing else consults it for a peer that is not present, so keeping it
        // costs nothing while a member is away.
    }

    private fun addConnectedPeer(peerId: String) {
        if (peerId.isBlank() || peerId == sessionManager.peerId.value) return
        _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        val existing = _peerUpdates.value[peerId]
        val nowMillis = System.currentTimeMillis()
        val hasFreshMeasurement = existing != null && !existing.meshOnlyPresence &&
            nowMillis - existing.lastSeen < PEER_EXPIRY_TIMEOUT_MILLIS
        // Never fall back to the peer id: it is an internal routing key, not
        // something a person can read or act on.
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }
        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = peerName,
            rssi = existing?.takeIf { hasFreshMeasurement }?.rssi,
            proximity = existing?.takeIf { hasFreshMeasurement }?.proximity ?: "Unknown",
            distance = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.distance,
            azimuth = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.azimuth,
            elevation = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.elevation,
            worldDirectionX = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.worldDirectionX,
            worldDirectionY = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.worldDirectionY,
            worldDirectionZ = existing?.takeIf { hasFreshMeasurement || peerId in retainedRangingPeerIds }?.worldDirectionZ,
            lastSeen = existing?.takeIf { hasFreshMeasurement }?.lastSeen ?: nowMillis,
            meshOnlyPresence = existing?.meshOnlyPresence ?: false,
            meshPresenceLastSeenAtMillis = existing?.meshPresenceLastSeenAtMillis,
            // Carried over, not reset: a transport connection says nothing about
            distanceSource = existing?.distanceSource,
            distanceAccuracyMeters = existing?.distanceAccuracyMeters,
            lastAdditionalRangingAtMillis = existing?.lastAdditionalRangingAtMillis,
            lastUwbMeasurementAtMillis = existing?.lastUwbMeasurementAtMillis,
            // when the peer joined, and clearing it here would drop a member to
            // the bottom of the arrival order every time its connection blipped.
            joinedAtMillis = existing?.joinedAtMillis
        )
        _peerUpdates.update { current -> current + (peerId to updated) }
        _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        syncFriend(updated)
    }

    private fun updateMeshPresence(peerId: String) {
        if (peerId.isBlank() || peerId == sessionManager.peerId.value || peerId in departedPeerIds) return
        connectedPeerIds.add(peerId)
        val state = sessionManager.sessionState.value
        if (!state.isHost && state.currentEvent?.hostPeerId == peerId) {
            _hostConnectionLostAtMillis.value = null
        }
        val nowMillis = System.currentTimeMillis()
        val existing = _peerUpdates.value[peerId]
        val hasFreshLocalMeasurement = existing != null && !existing.meshOnlyPresence &&
            nowMillis - existing.lastSeen < PEER_EXPIRY_TIMEOUT_MILLIS &&
            (existing.rssi != null || existing.lastUwbMeasurementAtMillis != null)
        val retainedUwb = peerId in retainedRangingPeerIds
        if (existing != null && !hasFreshLocalMeasurement && !retainedUwb) {
            rememberLastKnownPeerDirection(peerId)
        }
        val peerName = existing?.name ?: peerDisplayNames.labelFor(peerId) {
            context.getString(R.string.member_name, it)
        }
        val updated = if (existing == null) {
            RadarPeerUpdate(
                peerId = peerId,
                name = peerName,
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = nowMillis,
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
            )
        } else if (hasFreshLocalMeasurement) {
            existing.copy(meshPresenceLastSeenAtMillis = nowMillis)
        } else if (retainedUwb) {
            existing.copy(
                rssi = null,
                proximity = "Unknown",
                signalWeak = false,
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
            )
        } else {
            existing.copy(
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = nowMillis,
                signalWeak = false,
                meshOnlyPresence = true,
                meshPresenceLastSeenAtMillis = nowMillis,
                lastUwbMeasurementAtMillis = null,
                distanceSource = null,
                distanceAccuracyMeters = null,
                lastAdditionalRangingAtMillis = null,
            )
        }
        _peerUpdates.update { current -> current + (peerId to updated) }
        syncFriend(updated)
    }

    private fun updatePeerName(peerId: String, name: String) {
        if (name.isBlank() || peerId == sessionManager.peerId.value) return
        val existing = _peerUpdates.value[peerId]
        val updated = if (existing != null) {
            existing.copy(name = name)
        } else {
            RadarPeerUpdate(
                peerId = peerId,
                name = name,
                rssi = null,
                proximity = "Unknown",
                distance = null,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = System.currentTimeMillis()
            )
        }
        _peerUpdates.update { current -> current + (peerId to updated) }
        syncFriend(updated)
    }

    fun pauseSensors() = orientationTracker.pause()

    fun resumeSensors() {
        if (sessionManager.sessionState.value.currentEvent != null) {
            orientationTracker.resume()
        }
    }

    /**
     * Rewrites every peer's world direction from the device orientation.
     *
     * The tracker owns the orientation and decides when it moved far enough to be
     * worth redrawing; only the peers are this class's to update.
     */
    private fun refreshPeerWorldDirections() {
        val nowMillis = System.currentTimeMillis()
        _peerUpdates.value.values
            .filter { peer ->
                val measuredAt = peer.lastUwbMeasurementAtMillis
                measuredAt != null && !peer.directionIsLastKnown &&
                    UwbRetentionPolicy.isDirectionLastKnown(measuredAt, nowMillis)
            }
            .forEach { peer -> rememberLastKnownPeerDirection(peer.peerId) }

        _peerUpdates.update { current ->
            current.mapValues { (_, peer) ->
                val measuredAt = peer.lastUwbMeasurementAtMillis
                when {
                    peer.directionIsLastKnown -> peer
                    measuredAt != null && UwbRetentionPolicy.isDirectionLastKnown(measuredAt, nowMillis) ->
                        peer.copy(directionIsLastKnown = true)
                    peer.azimuth != null -> {
                        val (worldX, worldY, worldZ) =
                            orientationTracker.toWorldDirection(peer.azimuth, peer.elevation)
                        peer.copy(
                            worldDirectionX = worldX,
                            worldDirectionY = worldY,
                            worldDirectionZ = worldZ,
                        )
                    }
                    else -> peer
                }
            }
        }
    }

    private fun rememberLastKnownPeerDirection(peerId: String) {
        val peer = _peerUpdates.value[peerId] ?: return
        val worldX = peer.worldDirectionX ?: return
        val worldY = peer.worldDirectionY ?: return
        val detectedAt = peer.lastUwbMeasurementAtMillis ?: return
        val recordedAt = System.currentTimeMillis()
        val location = LastKnownPeerDirection(
            peerId = peerId,
            name = peer.name,
            worldDirectionX = worldX,
            worldDirectionY = worldY,
            distance = peer.distance,
            lastDetectedAtMillis = detectedAt,
            recordedAtMillis = recordedAt,
        )
        _lastKnownPeerDirections.update { current ->
            (current.filterNot { it.peerId == peerId } + location)
                .sortedByDescending { it.recordedAtMillis }
        }
    }

    /**
     * The UWB session currently open, so a change of focus can close it before
     * opening the next. Only ever one at a time: the platform is built for a
     * single ranging session, and holding several is what made ranging degrade
     * once a PingRing filled up.
     */
    private var activeFocusRangingJob: Job? = null
    private val peerUwbAddresses = mutableMapOf<String, String>()

    /**
     * Re-points the UWB radio at whoever is now focused.
     *
     * This is the payoff of the focus filter: instead of trying to range seven
     * people at once, the radio holds exactly one session and moves it. The
     * switch costs a fresh handshake, so it is debounced - a user flicking
     * through the member list should not open and abandon a session per tap.
     */
    private fun onFocusChanged() {
        val manager = uwbManager ?: return
        val scope = repositoryScope ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return

        val target = FocusPolicy.rangingTarget(
            focusPeerId = resolveFocusedPeer(),
            hostPeerId = event.hostPeerId,
            // Retained peers count as ranging candidates: their transport is
            // down but their UWB session is deliberately still open, and
            // excluding them here would cancel it on the very drop we want to
            // survive.
            connectedPeerIds = rangingCandidatePeerIds()
        )
        val targetId = (target as? FocusPolicy.RangingTarget.Peer)?.peerId
        if (activeFocusRangingJob?.isActive == true && targetId == activeFocusRangingTarget) return
        activeFocusRangingJob?.cancel()
        activeFocusRangingTarget = targetId
        if (targetId == null) {
            Log.i(TAG, "UWB ranging idle: no focused member to range")
            return
        }

        val address = peerUwbAddresses[targetId]
        if (address == null) {
            Log.i(TAG, "UWB ranging idle: $targetId has not shared a UWB address yet")
            return
        }

        val isHost = (target as FocusPolicy.RangingTarget.Peer).isHost
        activeFocusRangingJob = scope.launch {
            // Let a rapid change of selection settle before spending a
            // handshake on it.
            delay(UWB_FOCUS_SETTLE_MILLIS)
            try {
                Log.i(TAG, "UWB: ranging $targetId${if (isHost) " (host)" else ""} at $address")
                val eventId = sessionManager.sessionState.value.currentEvent?.id ?: return@launch
                val sessionId = eventId.hashCode()
                if (isHost) {
                    // The host holds the controller scope and therefore has to
                    // drive. It publishes the channel and STS key the guest
                    // needs, which is why this is the only direction that can
                    // invent them.
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    nearbyConnectionsManager.sendUwbParameters(
                        targetId,
                        UwbControllerParametersDto(
                            hostPeerId = localPeerId(),
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = sessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            sessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> updateUwbPosition(targetId, result) }
                } else {
                    // A guest ranging another guest offers itself as controller
                    // and routes the offer to the peer it names. Mesh relays can
                    // read the STS key; the named peer becomes the controlee
                    // (see [acceptIncomingRangingOffer]) using the exact
                    // parameters sent here, which makes the two ends agree.
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val eventId = sessionManager.sessionState.value.currentEvent?.id
                    if (eventId == null) {
                        Log.w(TAG, "UWB: no event to derive a session id from; skipping $targetId")
                        return@launch
                    }
                    // Both ends must end up on the same session id. The
                    // controlee takes this value verbatim from the offer, so
                    // agreement is guaranteed in that direction; deriving it
                    // from the unordered pair anyway means two guests that
                    // offer each other simultaneously still land on one id
                    // rather than two sessions that interleave on a channel.
                    val sessionId = (31L * eventId.hashCode() +
                        17L * (targetId.hashCode().toLong() + localPeerId().hashCode())).toInt()
                    nearbyConnectionsManager.sendUwbRangingOfferToHost(
                        UwbRangingOfferDto(
                            fromPeerId = localPeerId(),
                            targetPeerId = targetId,
                            controllerAddress = controller.localAddress.toString(),
                            channel = controller.uwbComplexChannel.channel,
                            preambleIndex = controller.uwbComplexChannel.preambleIndex,
                            sessionId = sessionId,
                            staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                        )
                    )
                    controller.prepareSession(
                        RangingParameters(
                            RangingParameters.CONFIG_UNICAST_DS_TWR,
                            sessionId,
                            0,
                            sessionKey,
                            null,
                            null,
                            listOf(UwbDevice.createForAddress(address)),
                            RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                        )
                    ).collect { result -> updateUwbPosition(targetId, result) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UWB focus session with $targetId failed; peer falls back to RSSI", e)
            }
        }
    }

    private var activeFocusRangingTarget: String? = null

    /**
     * Peers whose transport dropped while their UWB session was still live,
     * mapped to when the drop happened.
     *
     * The key set is what makes them eligible to keep being ranged: a retained
     * peer is not in [connectedPeerIds] (it cannot receive messages) but must
     * stay in the ranging candidate set, or the session would be torn down by
     * [FocusPolicy.rangingTarget] the moment transport dropped - which is
     * exactly the renegotiation this is meant to avoid.
     */
    private val retainedRangingPeerIds = mutableMapOf<String, Long>()

    /**
     * Positional labels for members with no display name yet, so a peer id is
     * never shown to a user. See [PeerDisplayNames].
     */
    private val peerDisplayNames = PeerDisplayNames()

    /**
     * When each peer last produced a real UWB ranging result.
     *
     * Consulted to decide whether a retained peer's session is still alive; see
     * [UwbRetentionPolicy.classify].
     */
    private val lastUwbMeasurementAt = mutableMapOf<String, Long>()

    /**
     * Peers this device may hold a UWB session with: the connected ones plus any
     * retained across a transport drop.
     */
    private fun rangingCandidatePeerIds(): Set<String> =
        connectedPeerIds.toSet() + retainedRangingPeerIds.keys

    private fun localPeerId(): String = sessionManager.peerId.value

    /**
     * Takes the controlee side of a ranging session another guest has offered.
     *
     * This is the other half of guest-to-guest ranging: [onFocusChanged] offers
     * itself as controller and sends the offer up to the host, and whoever is
     * named in it ends up here. Using the channel and STS key from the offer
     * verbatim is the whole point - they were generated by the other end, and
     * inventing different ones is exactly what produced silently wrong ranges
     * before this path existed.
     *
     * The session runs in its own coroutine rather than replacing
     * [activeFocusRangingJob], because the two roles are held by different
     * devices: this one is the controlee here even though it may
     * simultaneously be the controller for a different peer it is tracking.
     */
    private fun acceptIncomingRangingOffer(offer: UwbRangingOfferDto) {
        val manager = uwbManager ?: return
        val scope = repositoryScope ?: return
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (!event.useUwb) return
        // Only act on offers actually meant for this device, and never on one
        // this device sent.
        if (offer.targetPeerId != localPeerId() || offer.fromPeerId == localPeerId()) {
            return
        }
        // The offerer is only worth ranging if it is a member of this event.
        if (offer.fromPeerId != event.hostPeerId && offer.fromPeerId !in connectedPeerIds) {
            Log.w(
                TAG,
                "Ignoring a UWB ranging offer from ${offer.fromPeerId}: not a member of this event"
            )
            return
        }

        Log.i(
            TAG,
            "Accepting a UWB ranging offer from ${offer.fromPeerId} " +
                "(session ${offer.sessionId}, channel ${offer.channel})"
        )
        scope.launch {
            try {
                val controlee = manager.controleeSessionScope()
                controlee.prepareSession(
                    RangingParameters(
                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                        offer.sessionId,
                        0,
                        Base64.decode(offer.staticStsKey, Base64.NO_WRAP),
                        null,
                        UwbComplexChannel(offer.channel, offer.preambleIndex),
                        listOf(UwbDevice.createForAddress(offer.controllerAddress)),
                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                    )
                ).collect { result -> updateUwbPosition(offer.fromPeerId, result) }
            } catch (e: Exception) {
                Log.e(TAG, "UWB ranging as controlee for ${offer.fromPeerId} failed", e)
            }
        }
    }

    private fun startUwbRangingSession() {
        val manager = uwbManager
        val scope = repositoryScope ?: return
        val event = sessionManager.sessionState.value.currentEvent ?: return
        val localPeerId = sessionManager.peerId.value

        uwbSessionJob = scope.launch {
            if (manager == null || !event.useUwb) {
                Log.d(TAG, "UWB ranging disabled or unavailable")
                return@launch
            }
            try {
                if (sessionManager.sessionState.value.isHost) {
                    val controller = manager.controllerSessionScope()
                    val sessionKey = ByteArray(8).also(SecureRandom()::nextBytes)
                    val parameters = UwbControllerParametersDto(
                        hostPeerId = localPeerId,
                        controllerAddress = controller.localAddress.toString(),
                        channel = controller.uwbComplexChannel.channel,
                        preambleIndex = controller.uwbComplexChannel.preambleIndex,
                        sessionId = event.id.hashCode(),
                        staticStsKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    )

                    // Launch a separate coroutine to handle each peer connection
                    nearbyConnectionsManager.receivedPeerAddress.collect { peer ->
                        // Record the address regardless of whether a session is
                        // started: the focus-driven session above needs it too,
                        // and a peer that shared one while it was not the
                        // focused member is exactly the peer the user will want
                        // to focus next.
                        peerUwbAddresses[peer.peerId] = peer.address
                        onFocusChanged()

                        // Don't start a new session if one is already active for this peer
                        if (activeRangingJobs[peer.peerId]?.isActive == true) return@collect

                        activeRangingJobs[peer.peerId] = launch {
                            // Declared outside the try so the catch below can
                            // report how long the session survived.
                            val startedAt = System.currentTimeMillis()
                            try {
                                // Send our controller parameters back to the member
                                nearbyConnectionsManager.sendUwbParameters(peer.peerId, parameters)

                                // Instrumented because a full PingRing opens one
                                // session per guest on this single controller
                                // scope. Whether the platform tolerates several
                                // concurrent prepareSession calls is hardware
                                // dependent and undocumented, and a refusal shows
                                // up only as a peer that silently stops ranging.
                                // The count and the first-failure reason are what
                                // tell the two behaviours apart in a logcat
                                // capture, so log both.
                                Log.i(
                                    TAG,
                                    "Starting UWB session for peer ${peer.peerId} " +
                                        "(active sessions: ${activeRangingJobs.size}/${MAX_PARTICIPANTS - 1})"
                                )

                                // Start ranging with the member in a non-blocking flow
                                controller.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        parameters.sessionId,
                                        0,
                                        sessionKey,
                                        null,
                                        null,
                                        listOf(UwbDevice.createForAddress(peer.address)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> updateUwbPosition(peer.peerId, result) }
                            } catch (e: Exception) {
                                Log.e(
                                    TAG,
                                    "UWB session failed for peer ${peer.peerId} " +
                                        "after ${System.currentTimeMillis() - startedAt} ms; " +
                                        "this peer will fall back to RSSI-only distance",
                                    e
                                )
                            } finally {
                                activeRangingJobs.remove(peer.peerId)
                            }
                        }
                    }
                } else {
                    val controlee = manager.controleeSessionScope()

                    // Periodically send our peer address to the host until ranging starts
                    launch {
                        while (isActive) {
                            nearbyConnectionsManager.broadcastUwbParameters(
                                UwbPeerAddressDto(localPeerId, controlee.localAddress.toString())
                            )
                            delay(5000)
                        }
                    }

                    // Collect controller parameters from the host
                    nearbyConnectionsManager.receivedControllerParams.collect { controllerParams ->
                        // Don't start a new session if one is already active for the host
                        if (activeRangingJobs[controllerParams.hostPeerId]?.isActive == true) return@collect

                        activeRangingJobs[controllerParams.hostPeerId] = launch {
                            try {
                                controlee.prepareSession(
                                    RangingParameters(
                                        RangingParameters.CONFIG_UNICAST_DS_TWR,
                                        controllerParams.sessionId,
                                        0,
                                        Base64.decode(controllerParams.staticStsKey, Base64.NO_WRAP),
                                        null,
                                        UwbComplexChannel(controllerParams.channel, controllerParams.preambleIndex),
                                        listOf(UwbDevice.createForAddress(controllerParams.controllerAddress)),
                                        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                                    )
                                ).collect { result -> updateUwbPosition(controllerParams.hostPeerId, result) }
                            } catch (e: Exception) {
                                Log.e(TAG, "UWB session failed with host", e)
                            } finally {
                                activeRangingJobs.remove(controllerParams.hostPeerId)
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "UWB ranging unavailable; continuing with BLE proximity", exception)
            }
        }
    }

    private fun updateUwbPosition(peerId: String, result: RangingResult) {
        val position = (result as? RangingResult.RangingResultPosition)?.position ?: return
        // From here on this peer has a real measurement, so later BLE
        // advertisements must not replace it with an estimate.
        uwbRangedPeers.add(peerId)
        // The RSSI smoother is deliberately NOT cleared here. It used to be, and
        // that made UWB and BLE mutually exclusive - one number or the other.
        // But BLE is an *independent* measurement of the same geometry, which is
        // exactly what is needed to tell whether this UWB fix is obstructed: a
        // body blocking the first path biases the distance long and collapses the
        // signal at the same time. Keeping the smoother alive while ranging is
        // what lets RangingSourcePolicy cross-check the two. It costs nothing
        // extra - the adverts are already arriving for discovery - and it is the
        // whole of the obstruction check.
        val measuredAtMillis = System.currentTimeMillis()
        lastUwbMeasurementAt[peerId] = measuredAtMillis
        _lastKnownPeerDirections.update { current -> current.filterNot { it.peerId == peerId } }
        _peerUpdates.update { peers ->
            val current = peers[peerId] ?: return@update peers
            peers + (
                peerId to current.copy(
                    distance = position.distance?.value,
                    azimuth = position.azimuth?.value,
                    elevation = position.elevation?.value,
                    lastSeen = measuredAtMillis,
                    lastUwbMeasurementAtMillis = measuredAtMillis,
                    distanceSource = RangingSourcePolicy.RangingSource.UWB,
                    distanceAccuracyMeters = null,
                    lastAdditionalRangingAtMillis = null,
                    directionIsLastKnown = false,
                )
            )
        }
        refreshPeerWorldDirections()
    }

    private fun publishPeerRanging(wifiAwareMac: String? = null, gnssFix: GnssFixDto? = null) {
        val event = sessionManager.sessionState.value.currentEvent ?: return
        if (gnssFix != null && !event.useGnss) return
        nearbyConnectionsManager.broadcastPeerRanging(
            PeerRangingPayloadDto(event.id, localPeerId(), localRangingSequence.getAndIncrement(), wifiAwareMac, gnssFix)
        )
    }

    private fun hasFreshUwbMeasurement(peerId: String): Boolean =
        _peerUpdates.value[peerId]?.lastUwbMeasurementAtMillis?.let {
            System.currentTimeMillis() - it in 0..2_000L
        } == true

    private val MAX_LOCAL_RADAR_GNSS_DISTANCE_METERS = 100f

    private fun updateGnssDistances() {
        if (sessionManager.sessionState.value.currentEvent?.useGnss != true) return
        val local = gnssRanging.currentFix() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        peerGnssFixes.forEach { (peerId, entry) ->
            val remote = entry.first.copy(ageMillis = entry.first.ageMillis + now - entry.second)
            if (!GnssPolicy.isUsable(remote)) {
                peerGnssFixes.remove(peerId, entry)
            } else {
                val separation = GnssPolicy.separationMeters(local, remote)
                if (separation <= MAX_LOCAL_RADAR_GNSS_DISTANCE_METERS) {
                    updateAdditionalDistance(
                        peerId,
                        separation,
                        local.accuracyMeters + remote.accuracyMeters,
                        RangingSourcePolicy.RangingSource.GNSS,
                        measurementAgeMillis = maxOf(local.ageMillis, remote.ageMillis),
                    )
                } else {
                    val existing = _peerUpdates.value[peerId]
                    if (existing?.distanceSource == RangingSourcePolicy.RangingSource.GNSS) {
                        clearAdditionalDistances(RangingSourcePolicy.RangingSource.GNSS)
                    }
                }
            }
        }
    }

    private fun updateAdditionalDistance(
        peerId: String,
        distance: Float,
        accuracy: Float,
        source: RangingSourcePolicy.RangingSource,
        measurementAgeMillis: Long = 0L,
    ) {
        val state = sessionManager.sessionState.value
        val event = state.currentEvent ?: return
        if (peerId in departedPeerIds || peerId == localPeerId() ||
            (source == RangingSourcePolicy.RangingSource.GNSS && !event.useGnss) ||
            !distance.isFinite() || !accuracy.isFinite()
        ) return
        if (hasFreshUwbMeasurement(peerId)) return
        val existing = _peerUpdates.value[peerId]
        if (source == RangingSourcePolicy.RangingSource.GNSS &&
            existing?.distanceSource == RangingSourcePolicy.RangingSource.WIFI_RTT &&
            System.currentTimeMillis() - (existing.lastAdditionalRangingAtMillis ?: 0L) in 0..5_000L
        ) return
        if (existing == null) addConnectedPeer(peerId)
        rememberLastKnownPeerDirection(peerId)
        val now = System.currentTimeMillis()
        _peerUpdates.update { peers ->
            val peer = peers[peerId] ?: return@update peers
            peers + (peerId to peer.copy(
                distance = distance,
                distanceSource = source,
                distanceAccuracyMeters = accuracy,
                lastAdditionalRangingAtMillis = now - measurementAgeMillis,
                azimuth = null,
                elevation = null,
                worldDirectionX = null,
                worldDirectionY = null,
                worldDirectionZ = null,
                lastSeen = now,
                meshOnlyPresence = false,
                directionIsLastKnown = false,
                rangingConfidence = RangingConfidence.UNKNOWN,
            ))
        }
        _peerUpdates.value[peerId]?.let(::syncFriend)
    }

    private fun additionalDistanceLifetime(source: RangingSourcePolicy.RangingSource?): Long =
        if (source == RangingSourcePolicy.RangingSource.WIFI_RTT) 5_000L else GnssPolicy.MAX_FIX_AGE_MILLIS

    private fun clearAdditionalDistances(source: RangingSourcePolicy.RangingSource) {
        _peerUpdates.update { peers -> peers.mapValues { (_, peer) ->
            if (peer.distanceSource == source) peer.copy(
                distance = null, distanceSource = null, distanceAccuracyMeters = null,
                lastAdditionalRangingAtMillis = null,
            ) else peer
        } }
    }

    private fun expireAdditionalDistances(nowMillis: Long) {
        _peerUpdates.update { peers -> peers.mapValues { (_, peer) ->
            val measuredAt = peer.lastAdditionalRangingAtMillis
            if (measuredAt != null && nowMillis - measuredAt !in 0..additionalDistanceLifetime(peer.distanceSource)) {
                peer.copy(distance = null, distanceSource = null, distanceAccuracyMeters = null, lastAdditionalRangingAtMillis = null)
            } else peer
        } }
    }

    /**
     * Applies a scripted range report received over the LAN debug transport
     * (see tools/mock_radar.py).
     *
     * This performs exactly the update [updateUwbPosition] would for a real
     * report, so the code under test is the production one: the rings, the
     * compass-corrected arrow, the distance readout, the "Direction locked" /
     * "BLE proximity mode" switch, the sub-2 m haptic and the staleness sweep
     * all react to a scripted walk just as they would to a real one. Only the
     * measurement is fake, never the rendering.
     *
     * Unlike a UWB update this also *creates* the peer entry when it is
     * missing, because a device on the LAN debug transport never sees a BLE
     * scan result for the other device: without that, the radar would stay
     * empty on the emulator and the injected distances would be dropped.
     */
    private fun updateMockRanging(dto: MockRangingDto) {
        val peerId = dto.peerId
        if (peerId.isEmpty() || peerId == sessionManager.peerId.value) return
        if (sessionManager.sessionState.value.currentEvent == null) return

        // A scripted report carries a distance but no real signal strength, so
        // an RSSI is derived by inverting the curve the BLE scan path uses, via
        // RssiDistanceEstimator. Without it the hot/cold ring and the dBm
        // readout would sit at their "no signal" values and stop reacting to
        // the scripted movement, which is half of what the radar has to be
        // tested for. A scripted distance is a real measurement, so it also
        // pins the peer the way a UWB fix would.
        val existing = _peerUpdates.value[peerId]
        val rssi: Int? = dto.rssi
            ?: dto.distance?.let { RssiDistanceEstimator.rssiForDistance(it) }?.toInt()

        // Two quite different things can arrive here, and conflating them is
        // what made the scripted radar too clean to be useful:
        //
        //  - A distance (with or without an explicit dBm) is a ranging fix. It
        //    is precise by definition, so it pins the peer and clears any
        //    signal history: a UWB measurement outranks an estimate, and the
        //    two must not be blended.
        //  - An rssi with no distance is a *signal* reading, which is what a
        //    non-UWB peer actually provides. It goes through RssiSmoother on
        //    the same path a BLE advert takes, so scripted noise, outliers and
        //    dropouts exercise the real filter instead of bypassing it.
        val isRangingFix = dto.distance != null
        if (isRangingFix) {
            uwbRangedPeers.add(peerId)
            rssiSmoothers.remove(peerId)
        }
        // Mirrors the BLE path: the estimate is what the distance becomes, and
        // a null estimate means the peer has dropped below the noise floor, in
        // which case the last good distance stands rather than being invented.
        val rssiEstimate = if (isRangingFix) {
            null
        } else {
            rssi?.let { rssiSmoothers.getOrPut(peerId) { RssiSmoother() }.add(it, System.currentTimeMillis()) }
        }

        val updated = RadarPeerUpdate(
            peerId = peerId,
            name = dto.name?.takeIf { it.isNotBlank() } ?: existing?.name ?: peerDisplayNames.labelFor(peerId) {
                context.getString(R.string.member_name, it)
            },
            rssi = rssi,
            proximity = when {
                rssi == null -> "Unknown"
                rssi >= -70 -> "Hot"
                else -> "Cold"
            },
            distance = dto.distance ?: rssiEstimate ?: existing?.distance,
            azimuth = dto.azimuth ?: existing?.azimuth,
            elevation = dto.elevation ?: existing?.elevation,
            worldDirectionX = existing?.worldDirectionX,
            worldDirectionY = existing?.worldDirectionY,
            worldDirectionZ = existing?.worldDirectionZ,
            lastSeen = System.currentTimeMillis(),
            // Mock ranging reports carry no advert, so this peer has no arrival
            // time to learn from one. Carrying the existing value keeps it
            // ranked where BLE put it rather than dropping it to last.
            joinedAtMillis = existing?.joinedAtMillis
        )

        _peerUpdates.update { current ->
            current + (peerId to updated)
        }
        syncFriend(updated)
        refreshPeerWorldDirections()
    }
}
