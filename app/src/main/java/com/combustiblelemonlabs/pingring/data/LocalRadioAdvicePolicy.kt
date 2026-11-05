package com.combustiblelemonlabs.pingring.data

import com.combustiblelemonlabs.pingring.data.discovery.ConnectivityStatus
/**
 * Which local-radio problems are worth telling the member about, and in what
 * order.
 *
 * These are conditions this device can diagnose about itself, and each one has
 * something the member can do about it. Nothing here is a claim about why the
 * PingRing is not connecting - the host may simply be out of range, and no
 * amount of local state can tell the two apart.
 *
 * ## The wording constraint that shapes all of it
 *
 * PingRing's promise is that it works with no network at all: no SIM, no Wi-Fi,
 * no account. `docs/architecture.md` calls offline "the product, not a
 * limitation". So **no advice here may say a member cannot reach anybody.**
 * Every one of these states leaves the app working; what they cost is range,
 * ranging accuracy and mesh capacity. Saying otherwise would contradict the
 * product's central claim to warn about something that is not broken.
 *
 * In particular "you are on mobile data" is never an explanation. Being on
 * mobile data is normal and harmless; it is only ever a *symptom* that Wi-Fi was
 * turned off, and the advice names the Wi-Fi because that is the thing with a
 * switch behind it.
 *
 * ## Precedence
 *
 * When both radios are off, only the Bluetooth notice is shown. Bluetooth is
 * what discovery and every connection run over, so it is the blocking problem;
 * a second card about Wi-Fi underneath it tells the member nothing they can act
 * on until the first one is dealt with, and two error cards at the top of a
 * member list reads as a broken app rather than one switch that needs flipping.
 *
 * Pure and Android-free, so the precedence is assertable without a radio.
 */
object LocalRadioAdvicePolicy {

    enum class Advice {
        /**
         * The Bluetooth radio is off. Discovery cannot run at all.
         *
         * The blocking problem, so it suppresses every other notice.
         */
        BLUETOOTH_OFF,

        /**
         * Bluetooth is on but Wi-Fi is off, so Nearby Connections is limited to
         * its Bluetooth transport.
         *
         * Framed as what is *lost* - range, Wi-Fi RTT ranging, mesh capacity -
         * because nothing is broken and the member can still use the ring.
         */
        WIFI_OFF,

        /**
         * The hardware cannot do signed BLE identity advertising.
         *
         * Not a fault the member can fix, so it is worded as a capability
         * statement rather than an error, and it never suppresses anything.
         */
        BLE_IDENTITY_UNAVAILABLE,
    }

    /**
     * The notices to show, most urgent first.
     *
     * Empty is a normal result and means nothing needs saying.
     */
    fun adviceFor(connectivity: ConnectivityStatus): List<Advice> {
        if (!connectivity.isBluetoothEnabled) {
            // Only the blocking one. See the precedence note above.
            return listOf(Advice.BLUETOOTH_OFF)
        }

        return buildList {
            if (!connectivity.isWifiEnabled) add(Advice.WIFI_OFF)
            if (!connectivity.supportsBleIdentity) add(Advice.BLE_IDENTITY_UNAVAILABLE)
        }
    }
}