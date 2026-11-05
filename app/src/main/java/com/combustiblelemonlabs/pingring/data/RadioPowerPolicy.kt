package com.combustiblelemonlabs.pingring.data
import com.combustiblelemonlabs.pingring.data.ranging.BleRangePolicy
import com.combustiblelemonlabs.pingring.data.ranging.UwbRetentionPolicy

/**
 * How hard each radio should work, given what the device is actually doing.
 *
 * Three radios compete on one battery. BLE scans continuously for as long as a
 * PingRing is live, Nearby Connections brings up Wi-Fi Direct for the payload
 * channel, and UWB holds a ranging session open for [UwbRetentionPolicy]
 * after a member drops. None of that is free, and nothing in the codebase
 * measured what it costs.
 *
 * This makes the trade-off explicit and adjustable instead of fixed: the knobs
 * the platform does expose are the BLE scan mode, the number of concurrent
 * scan passes, and how long a UWB session is held open. What it cannot do is
 * turn Wi-Fi Direct down - Nearby owns that transport and offers no setting,
 * which is the same finding that removed the High Bandwidth switch.
 *
 * Pure and Android-free so the rules are directly testable, in the manner of
 * [BleRangePolicy] and [ConnectionMethodPolicy].
 */
object RadioPowerPolicy {

    /**
     * How aggressively to scan.
     *
     * Not an Android constant so this stays testable - the values map onto
     * `ScanSettings.SCAN_MODE_*` at the call site.
     */
    enum class ScanEffort {
        /** The platform's default. Cheapest, and may miss adverts. */
        BALANCED,

        /** `SCAN_MODE_LOW_LATENCY`. Finds members faster, costs visibly more. */
        ACTIVE,
    }

    /** What the policy decided, in the terms the platform actually speaks. */
    data class Plan(
        val scanEffort: ScanEffort,
        val scanPassCount: Int,
        val uwbRetentionMillis: Long,
    ) {
        val usesSecondScanPass: Boolean get() = scanPassCount > 1
    }

    /**
     * What the device is currently doing that bears on the cost.
     *
     * [membersWatching] is how many peers are on the radar. A ring of one does
     * not need the radio settings of a ring of eight: the pass count exists to
     * raise the chance a member is seen, and that matters more as the ring
     * fills up and a miss is a member who simply does not appear.
     */
    data class Load(
        val isHost: Boolean,
        val memberCount: Int,
        val uwbActive: Boolean,
        val hotspotActive: Boolean,
        val onBattery: Boolean = true,
        val batterySaverEnabled: Boolean = false,
    )

    /**
     * One extra BLE scan pass, on capable hardware.
     *
     * Two is the useful maximum: more passes contend for the same adapter and
     * make throughput *worse* rather than better, while each one is a real
     * power cost on a phone that may scan for hours.
     */
    const val MAX_SCAN_PASSES = 2

    /**
     * How long a UWB session is held open after a transport drop.
     *
     * Holding it is what makes walking back instant instead of renegotiating,
     * but a UWB radio left open is one of the more expensive things on this
     * list. [UwbRetentionPolicy] owns the correctness of the window - a member
     * who has gone away must not be reported as merely unreachable forever -
     * so this only decides *how long* it is worth paying for.
     */
    const val UWB_RETENTION_ACTIVE_MILLIS = 45_000L

    /**
     * The shortened window used when the radios are already busy.
     *
     * Not zero: dropping the session immediately would give back the instant
     * reconnect for anyone who steps a few metres away, which is the common
     * case this feature exists for. Half is the compromise - long enough to
     * cover a trip to the bar and back, short enough that a phone left in a
     * pocket stops ranging.
     */
    const val UWB_RETENTION_BUSY_MILLIS = 20_000L

    /** Below this many members, the second scan pass is not worth its cost. */
    const val PASS_THRESHOLD_MEMBERS = 3

    /**
     * Chooses the settings for the current load.
     *
     * [supportsExtendedAdvertising] decides whether a second pass can actually
     * differ from the first: without it both passes would be identical requests
     * to the same adapter, which is the pointless contention [MAX_SCAN_PASSES]
     * exists to avoid.
     */
    fun plan(
        load: Load,
        supportsExtendedAdvertising: Boolean,
    ): Plan {
        val crowded = load.memberCount >= PASS_THRESHOLD_MEMBERS
        val secondPass = supportsExtendedAdvertising && crowded && !load.batterySaverEnabled

        return Plan(
            scanEffort = when {
                load.batterySaverEnabled || (load.hotspotActive && load.uwbActive && !load.onBattery) -> ScanEffort.BALANCED
                else -> ScanEffort.ACTIVE
            },
            scanPassCount = if (secondPass) MAX_SCAN_PASSES else 1,
            uwbRetentionMillis = if (load.batterySaverEnabled) {
                UWB_RETENTION_BUSY_MILLIS / 2
            } else if (load.uwbActive && (load.hotspotActive || !load.onBattery)) {
                UWB_RETENTION_BUSY_MILLIS
            } else {
                UWB_RETENTION_ACTIVE_MILLIS
            },
        )
    }

    /** A one-line summary for logcat, so a device run can be checked after the fact. */
    fun describe(plan: Plan): String =
        "scan=${plan.scanEffort} passes=${plan.scanPassCount} " +
            "uwbRetention=${plan.uwbRetentionMillis}ms"
}
