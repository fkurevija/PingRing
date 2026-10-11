package com.fkurevija.pingring.data.radar
import com.fkurevija.pingring.data.radar.RadarPeerUpdate
/**
 * The order the member list is drawn in.
 *
 * Strongest signal first, because "who is closest to me" is the question the
 * list exists to answer and the list had no answer to it. Rows arrived in
 * whatever order the ranging pipeline happened to emit, which is close to
 * arbitrary: a peer that refreshed its reading moved for no reason the user
 * could see, so the list reshuffled while somebody was trying to read it.
 *
 * Two decisions here are not obvious and both are load-bearing:
 *
 *  - **A peer we cannot hear sorts last, not first.** A missing RSSI is an
 *    absence of information, not a measurement of distance, and the only thing
 *    worse than being wrong about where somebody is being *certain* about it.
 *    Ranking an unheard peer above a heard one would put a member who is
 *    merely quiet above one standing next to you.
 *
 *  - **Ties break on name, then peer id.** Two members commonly sit on the
 *    same dBm, and an unstable sort would swap them on every recomposition.
 *    That is the same reshuffling problem in miniature, and a list whose order
 *    changes when nothing about the members changed cannot be scanned.
 *
 * Pure and Android-free: it takes data and returns data, so the rule is
 * exhaustively testable without a radio.
 */
object PeerListOrderPolicy {

    /**
     * [peers] reordered nearest-first.
     *
     * Returns a new list; the input is not modified. Every peer appears exactly
     * once, so this is a reordering and never a filter - dropping a member
     * because they have no RSSI would silently hide them from the ring.
     */
    fun bySignalStrength(peers: List<RadarPeerUpdate>): List<RadarPeerUpdate> =
        peers.sortedWith(
            // false (0) before true (1): a measured signal outranks an absent one.
            compareBy<RadarPeerUpdate> { it.rssi == null }
                // BLE RSSI is negative dBm, so numerically larger is stronger.
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name.lowercase() }
                .thenBy { it.peerId }
        )
}