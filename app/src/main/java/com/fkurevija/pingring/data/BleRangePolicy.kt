package com.fkurevija.pingring.data

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.os.Build

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
 * project whose whole premise is the radios working. So the levers below are
 * the honest, supported ones.
 */
object BleRangePolicy {

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
     *
     * Kept as the ceiling the app can ever ask for. The live count is
     * [RadioPowerPolicy]'s to decide, since it weighs how full the ring is -
     * a ring of two does not need the settings of a ring of eight.
     */
    fun scanPassCount(supportsExtended: Boolean): Int = if (supportsExtended) 2 else 1

    /**
     * Picks the scan settings for a given pass.
     *
     * [effort] comes from [RadioPowerPolicy] rather than being fixed here, so
     * the mode can drop to the platform default when the other radios are
     * already busy. The aggressive match mode is kept either way: it costs
     * nothing extra, and it is what stops a peer already connected elsewhere
     * being reported once and then dropped.
     *
     * The two passes differ in one respect. The first is `legacy = false`, so
     * on hardware supporting extended advertising it also picks up BLE 5
     * adverts; the second stays on legacy as a safety net for a peer whose radio
     * only ever advertises the classic way. That is the most coverage the
     * public SDK allows.
     */
    fun scanSettingsForPass(
        pass: Int,
        supportsExtended: Boolean,
        effort: RadioPowerPolicy.ScanEffort = RadioPowerPolicy.ScanEffort.ACTIVE,
    ): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(
                when (effort) {
                    RadioPowerPolicy.ScanEffort.ACTIVE -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    RadioPowerPolicy.ScanEffort.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                }
            )
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

    /** V5 signed service-data size; this deliberately exceeds the legacy limit. */
    const val MAX_LEGACY_SERVICE_DATA_BYTES = 31
    const val LEGACY_V4_SERVICE_DATA_BYTES = 28
    const val SERVICE_DATA_BYTES = BleIdentityAdvertisement.TOTAL_BYTES
    /** UUID and AD structure bytes plus a conservative allowance for flags. */
    const val EXTENDED_AD_STRUCTURE_OVERHEAD_BYTES = 39
    const val MIN_EXTENDED_AD_DATA_BYTES = SERVICE_DATA_BYTES + EXTENDED_AD_STRUCTURE_OVERHEAD_BYTES
    const val MAX_EXTENDED_AD_DATA_BYTES = 1650

    const val LEGACY_HEADROOM_BYTES = MAX_LEGACY_SERVICE_DATA_BYTES - LEGACY_V4_SERVICE_DATA_BYTES

    fun fitsInLegacyAdvertising(): Boolean = SERVICE_DATA_BYTES <= MAX_LEGACY_SERVICE_DATA_BYTES

    fun fitsInExtendedAdvertising(): Boolean = MIN_EXTENDED_AD_DATA_BYTES <= MAX_EXTENDED_AD_DATA_BYTES
}
