package com.fkurevija.pingring.data

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * How far PingRing can see over BLE, and the radio settings that decide it.
 *
 * Kept separate from [DiscoveryRepository] so the decisions can be asserted
 * without a radio, and so the reasoning survives independently of the
 * Bluetooth plumbing.
 *
 * A note on what this deliberately does *not* do. Coded PHY (LE Coded) is the
 * one setting that would genuinely multiply range, and it is what production
 * long-range BLE apps use. It cannot be used here: the public SDK exposes no
 * way to request it. `ScanSettings.setPhy`
 * exists but the only public constant is `PHY_LE_ALL_SUPPORTED`, which hands
 * the choice to the controller, and `AdvertiseSettings.Builder` has no public
 * `setPreferredPhy` at all. Both were verified against the API 37 android.jar
 * rather than assumed. Selecting LE Coded therefore needs a hidden-API or
 * reflection workaround, which is not a trade this codebase should make in a
 * privacy-first app. So the levers below are the honest, supported ones.
 */
object BleRangePolicy {

    private const val TAG = "BleRangePolicy"

    /**
     * True when this device's radio and platform can act on long-range hints.
     *
     * Coded PHY cannot be *selected* from the public SDK, so this is reported
     * as a capability signal (and logged) rather than used to drive a setting.
     */
    fun isCodedPhyUsable(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { adapter.isLeCodedPhySupported }.getOrDefault(false)
        } else {
            false
        }
    }

    /**
     * True when extended (BLE 5.0) advertising is available, which is the
     * public-API route to a larger advertising payload and a longer interval
     * budget. PingRing's payload is 20 bytes, so the size win is not
     * needed, but extended advertising is a genuinely better discovery
     * mechanism than legacy and costs nothing to prefer.
     */
    fun supportsExtendedAdvertising(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        } else {
            false
        }
    }

    /**
     * How many concurrent scan passes to run.
     *
     * Android's scanner is a single queue per adapter and drops reports when it
     * cannot keep up, which is how a peer can be missed while actively being
     * scanned for. Running two passes with different settings raises the chance
     * that any one advertisement is seen.
     *
     * Two is the useful maximum. More than that and the extra requests contend
     * for the same adapter, which makes throughput worse rather than better,
     * and it is a real power cost on a phone that may be scanning for hours.
     */
    fun scanPassCount(supportsExtended: Boolean): Int = if (supportsExtended) 2 else 1



    /**
     * Picks the scan settings for a given pass.
     *
     * Both passes use the most aggressive scan mode, because PingRing is
     * looking for a small, infrequent service-data advert and the limiting
     * factor is how often the radio is allowed to look, not the airtime cost.
     *
     * They differ in one respect. The first pass is `legacy = false`, so on
     * hardware that supports extended advertising it also picks up BLE 5
     * adverts; the second pass stays on legacy as a safety net for a peer whose
     * radio only ever advertises the classic way. That combination is the most
     * coverage the public SDK allows.
     */
    fun scanSettingsForPass(pass: Int, supportsExtended: Boolean): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // Match as many adverts as the controller will give us, rather
            // than stopping at the first: a peer that is already connected
            // elsewhere can otherwise be reported once and then dropped.
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsExtended) {
            builder.setLegacy(pass > 0)
        }
        return builder.build()
    }

    /**
     * Advertise settings.
     *
     * Max TX power was already the setting here, which is half the link budget
     * and was never the problem. The public SDK offers no further lever: there
     * is no `setPreferredPhy`, so the controller picks the advertising PHY
     * itself. This method exists so that the reasoning lives in one place and
     * the next person does not have to re-derive that the obvious setting is
     * already at its ceiling.
     */
    fun advertiseSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(false)
        .build()

    /**
     * Service-data size PingRing puts in an advertisement, and the legacy
     * advertising ceiling it has to stay under.
     *
     * Worth asserting rather than assuming: growing this payload past the
     * legacy limit makes the advert silently untransmittable, which presents
     * as a total range failure with no error anywhere.
     *
     * The two are one budget rather than two numbers that happen to agree -
     * [BleAdvertisementPayload.TOTAL_BYTES] is the payload's own size and must
     * equal this, or the advert is built at a size nothing is testing against
     * the limit that would reject it.
     */
    const val MAX_LEGACY_SERVICE_DATA_BYTES = 31

    const val SERVICE_DATA_BYTES = 20

    /**
     * How much room is left before the legacy ceiling, in bytes.
     *
     * Named so the next field to be added can say what it costs. BLE 5 extended
     * advertising would allow far more, but requiring it would drop every
     * device that only does legacy - so growth here is deliberately bounded and
     * a *future* decision, not something to spend by default.
     */
    const val LEGACY_HEADROOM_BYTES = MAX_LEGACY_SERVICE_DATA_BYTES - SERVICE_DATA_BYTES

    fun fitsInLegacyAdvertising(): Boolean = SERVICE_DATA_BYTES <= MAX_LEGACY_SERVICE_DATA_BYTES

    /** Human-readable summary of the active configuration, for logcat and settings. */
    fun describe(context: Context): String {
        val extended = supportsExtendedAdvertising(context)
        return "BLE range: passes=${scanPassCount(extended)}, " +
            "extendedAdvertising=$extended, codedPhyHardware=${isCodedPhyUsable(context)} (not selectable via public API)"
    }

    internal fun logSummary(context: Context) {
        Log.i(TAG, describe(context))
    }
}