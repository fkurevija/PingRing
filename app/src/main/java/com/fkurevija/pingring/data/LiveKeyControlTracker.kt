package com.fkurevija.pingring.data

internal class LiveKeyControlTracker {
    private data class PendingProof(
        val peerId: String,
        val publicKey: String,
        val challenge: ByteArray,
        val requestedAtMillis: Long,
    )

    private data class VerifiedProof(val publicKey: String, val verifiedAtMillis: Long)

    private val pending = mutableMapOf<String, PendingProof>()
    private val verified = mutableMapOf<String, VerifiedProof>()

    @Synchronized
    fun hasPending(endpointId: String, nowMillis: Long): Boolean = pending[endpointId]?.let {
        nowMillis - it.requestedAtMillis in 0 until CHALLENGE_TIMEOUT_MILLIS
    } == true

    @Synchronized
    fun begin(endpointId: String, peerId: String, publicKey: String, challenge: ByteArray, nowMillis: Long) {
        pending[endpointId] = PendingProof(peerId, publicKey, challenge.copyOf(), nowMillis)
    }

    @Synchronized
    fun complete(
        endpointId: String,
        peerId: String,
        publicKey: String,
        challenge: ByteArray,
        nowMillis: Long,
    ): Boolean {
        val proof = pending[endpointId] ?: return false
        if (!hasPending(endpointId, nowMillis) || proof.peerId != peerId || proof.publicKey != publicKey ||
            !proof.challenge.contentEquals(challenge)
        ) return false
        pending.remove(endpointId)
        verified[peerId] = VerifiedProof(publicKey, nowMillis)
        return true
    }

    @Synchronized
    fun isFresh(peerId: String, publicKey: String, nowMillis: Long): Boolean = verified[peerId]?.let {
        it.publicKey == publicKey && nowMillis - it.verifiedAtMillis in 0 until PROOF_VALIDITY_MILLIS
    } == true

    @Synchronized
    fun invalidate(endpointId: String, peerId: String?) {
        pending.remove(endpointId)
        peerId?.let(verified::remove)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        verified.clear()
    }

    companion object {
        const val CHALLENGE_TIMEOUT_MILLIS = 15_000L
        const val PROOF_VALIDITY_MILLIS = 10_000L
    }
}