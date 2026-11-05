package com.combustiblelemonlabs.pingring.data

/**
 * A discovery hint only. SAS confirmation and join authorization still establish trust.
 * Hosts announce their role through Nearby so admission does not depend on BLE ranging.
 */
internal data class NearbyDiscoveryIdentity(val peerId: String, val isHost: Boolean) {
    companion object {
        private const val HOST_PREFIX = "pingring-host:"

        fun encode(peerId: String, isHost: Boolean): String =
            if (isHost) "$HOST_PREFIX$peerId" else peerId

        fun decode(endpointName: String): NearbyDiscoveryIdentity? {
            val isHost = endpointName.startsWith(HOST_PREFIX)
            val peerId = if (isHost) endpointName.removePrefix(HOST_PREFIX) else endpointName
            if (!peerId.matches(Regex("[0-9a-fA-F]{8}"))) return null
            return NearbyDiscoveryIdentity(peerId, isHost)
        }

        fun hostCandidate(
            knownHostPeerId: String?,
            discoveredHostPeerId: String?,
            identity: NearbyDiscoveryIdentity,
            localPeerId: String,
            isLocalHost: Boolean,
        ): String? {
            if (isLocalHost) return knownHostPeerId
            return knownHostPeerId ?: discoveredHostPeerId
                ?: identity.peerId.takeIf { identity.isHost && it != localPeerId }
        }
    }
}
