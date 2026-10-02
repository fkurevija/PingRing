package com.fkurevija.pingring.data

/** Hysteretic warning state for one peer's smoothed BLE signal. */
internal class BleSignalWarningTracker(
    private val warningThresholdDbm: Float = WARNING_THRESHOLD_DBM,
    private val recoveryThresholdDbm: Float = RECOVERY_THRESHOLD_DBM,
    private val warningDurationMillis: Long = WARNING_DURATION_MILLIS,
) {
    private val weakSinceByPeer = mutableMapOf<String, Long>()
    private val warningPeerIds = mutableSetOf<String>()

    @Synchronized
    fun update(peerId: String, smoothedRssiDbm: Float?, nowMillis: Long): Boolean {
        if (smoothedRssiDbm == null) {
            if (peerId !in warningPeerIds) weakSinceByPeer.remove(peerId)
            return peerId in warningPeerIds
        }
        if (smoothedRssiDbm >= recoveryThresholdDbm) {
            weakSinceByPeer.remove(peerId)
            warningPeerIds.remove(peerId)
            return false
        }
        if (smoothedRssiDbm > warningThresholdDbm) {
            if (peerId !in warningPeerIds) weakSinceByPeer.remove(peerId)
            return peerId in warningPeerIds
        }

        val weakSince = weakSinceByPeer.getOrPut(peerId) { nowMillis }
        if (nowMillis - weakSince >= warningDurationMillis) warningPeerIds.add(peerId)
        return peerId in warningPeerIds
    }

    @Synchronized
    fun remove(peerId: String) {
        weakSinceByPeer.remove(peerId)
        warningPeerIds.remove(peerId)
    }

    @Synchronized
    fun clear() {
        weakSinceByPeer.clear()
        warningPeerIds.clear()
    }

    companion object {
        const val WARNING_THRESHOLD_DBM = -85f
        const val RECOVERY_THRESHOLD_DBM = -80f
        const val WARNING_DURATION_MILLIS = 1_500L
    }
}