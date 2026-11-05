package com.combustiblelemonlabs.pingring.data.transport

import android.util.Log
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.combustiblelemonlabs.pingring.data.MeshDuplicateCache
import com.combustiblelemonlabs.pingring.data.PayloadSink
import com.combustiblelemonlabs.pingring.data.RelayCodec

/**
 * Owns the Nearby payload callback and hands authenticated envelopes to the
 * inbound mesh dispatcher. Connection state and payload side effects remain
 * supplied by the transport.
 */
internal class NearbyMessageReceiver(
    private val tag: String,
    private val endpointPeerId: (String) -> String?,
    events: PayloadSink,
    duplicateCache: MeshDuplicateCache,
    state: NearbyInboundMeshDispatcher.State,
    callbacks: NearbyInboundMeshDispatcher.Callbacks,
) {
    private val dispatcher = NearbyInboundMeshDispatcher(tag, state, events, duplicateCache, callbacks)

    val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            receive(endpointId, payload.asBytes(), payload.type)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    internal fun receive(endpointId: String, bytes: ByteArray?, payloadType: Int? = null) {
        if (bytes == null) {
            Log.w(tag, "Ignoring a non-bytes payload (type=$payloadType) from $endpointId")
            return
        }
        // Explicit UTF-8, matching how it was written with toByteArray().
        // String(ByteArray) would use the platform default charset, which
        // is only ever accidentally right.
        val json = bytes.toString(Charsets.UTF_8)
        val peer = endpointPeerId(endpointId) ?: "?"
        Log.d(tag, "Received ${bytes.size} bytes from $endpointId [peer=$peer]")

        val envelope = RelayCodec.decodeEnvelope(json).getOrElse { error ->
            Log.e(tag, "Could not parse the envelope from $endpointId: ${error.message}", error)
            null
        }
        if (envelope != null && envelope.payload != null) {
            // The dispatcher logs when a decoded envelope cannot be delivered.
            dispatcher.dispatch(envelope, endpointId)
            return
        }

        // A mesh payload without its signed routing envelope cannot prove
        // its origin or participate in deduplication/hop limits.
        val legacy = RelayCodec.decodeUnwrapped(json)
        Log.w(
            tag,
            if (legacy == null) "Unrecognised ${bytes.size}-byte payload from $endpointId, dropped"
            else "Unwrapped ${legacy::class.simpleName} payload from $endpointId lacks mesh authentication, dropped"
        )
    }
}
