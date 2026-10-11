package com.fkurevija.pingring.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import com.fkurevija.pingring.data.session.Event

/**
 * Owns the relay TCP connection and its line-oriented read/write lifecycle.
 * Relay protocol decisions remain in [LanDebugConnectionsManager].
 */
internal class LanDebugSocketLifecycle(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val tag: String,
) {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private val writeLock = Any()
    private val pendingEnvelopes = ArrayDeque<RelayEnvelope>()

    suspend fun run(
        generation: Int,
        isCurrentGeneration: (Int) -> Boolean,
        eventWasClosed: () -> Boolean,
        joinEnvelope: RelayEnvelope,
        heartbeatEnvelope: () -> RelayEnvelope,
        onConnected: suspend () -> Unit,
        onLine: (String) -> Unit,
        onHeartbeat: () -> Unit,
        reconnectDelayMillis: Long,
        heartbeatIntervalMillis: Long,
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                Log.d(tag, "Connecting to relay at $host:$port (gen $generation)")
                val newSocket = Socket(host, port)
                if (!isCurrentGeneration(generation)) {
                    Log.w(tag, "Generation $generation superseded, discarding")
                    runCatching { newSocket.close() }
                    break
                }
                socket = newSocket
                val newWriter = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                writer = newWriter
                Log.i(tag, "Connected to relay (gen $generation)")
                writeEnvelopeNow(newWriter, joinEnvelope)
                flushPendingEnvelopes()
                onConnected()

                val heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(heartbeatIntervalMillis)
                        if (!isCurrentGeneration(generation)) break
                        val currentWriter = writer ?: break
                        if (!writeEnvelopeNow(currentWriter, heartbeatEnvelope())) break
                        onHeartbeat()
                    }
                }
                try {
                    val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        Log.d(tag, "Received ${line.length} characters from relay")
                        onLine(line)
                        if (eventWasClosed()) {
                            Log.i(tag, "Relay closed event; not retrying (gen $generation)")
                            break
                        }
                    }
                    Log.w(tag, "Relay connection closed, retrying… (gen $generation)")
                } finally {
                    heartbeatJob.cancel()
                }
            } catch (e: SocketException) {
                Log.w(tag, "Relay socket error: ${e.message} (gen $generation)")
            } catch (e: Exception) {
                Log.e(tag, "Relay connection failed (gen $generation)", e)
            }
            if (eventWasClosed()) {
                Log.i(tag, "Event was closed by host, stopping retry loop (gen $generation)")
                break
            }
            if (!isCurrentGeneration(generation)) {
                Log.w(tag, "Generation $generation superseded after disconnect, stopping retry loop")
                break
            }
            socket = null
            writer = null
            delay(reconnectDelayMillis)
        }
    }

    fun currentWriter(): BufferedWriter? = writer

    fun close() {
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    fun announce(envelope: RelayEnvelope): Boolean {
        val activeWriter = writer ?: return false
        return writeEnvelopeNow(activeWriter, envelope)
    }

    fun send(envelope: RelayEnvelope, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val activeWriter = writer
            if (activeWriter == null) {
                if (onComplete != null) {
                    Log.w(tag, "Not connected to relay; cannot send ${envelope.type} before shutdown")
                    onComplete()
                    return@launch
                }
                Log.w(tag, "Not connected to relay, queuing ${envelope.type} envelope")
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
                return@launch
            }
            if (!writeEnvelopeNow(activeWriter, envelope)) {
                synchronized(writeLock) { pendingEnvelopes.addLast(envelope) }
            }
            onComplete?.invoke()
        }
    }

    fun sendInOrder(envelopes: List<RelayEnvelope>) {
        if (envelopes.isEmpty()) return
        scope.launch {
            val activeWriter = writer
            if (activeWriter == null) {
                synchronized(writeLock) { pendingEnvelopes.addAll(envelopes) }
                return@launch
            }
            for (index in envelopes.indices) {
                if (writeEnvelopeNow(activeWriter, envelopes[index])) continue
                synchronized(writeLock) {
                    for (pending in envelopes.subList(index, envelopes.size)) {
                        pendingEnvelopes.addLast(pending)
                    }
                }
                break
            }
        }
    }

    private fun writeEnvelopeNow(activeWriter: BufferedWriter, envelope: RelayEnvelope): Boolean {
        val json = RelayCodec.encodeEnvelope(envelope)
        synchronized(writeLock) {
            return try {
                activeWriter.write(json)
                activeWriter.newLine()
                activeWriter.flush()
                Log.d(tag, "Sent ${json.length} characters to relay")
                true
            } catch (e: Exception) {
                Log.e(tag, "Failed to send to relay, queuing for retry", e)
                false
            }
        }
    }

    private fun flushPendingEnvelopes() {
        val toFlush = synchronized(writeLock) {
            val copy = pendingEnvelopes.toList()
            pendingEnvelopes.clear()
            copy
        }
        if (toFlush.isNotEmpty()) {
            Log.i(tag, "Flushing ${toFlush.size} queued envelope(s) after reconnect")
        }
        sendInOrder(toFlush)
    }
}
