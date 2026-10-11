package com.fkurevija.pingring.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.fkurevija.pingring.data.BleIdentityAdvertisement
import com.fkurevija.pingring.data.BleIdentityCrypto
import com.fkurevija.pingring.data.session.BleIdentityKeyPayloadDto
import com.fkurevija.pingring.data.ranging.BleRangePolicy
import com.fkurevija.pingring.data.ranging.BleRangeProbe
import com.fkurevija.pingring.data.NearbyConnectionsManager
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.RadioPowerPolicy
import com.fkurevija.pingring.data.session.SessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.fkurevija.pingring.data.DiscoveryRepository
import com.fkurevija.pingring.data.session.SessionState

/** Scans for signed identity advertisements and forwards verified peer sightings. */
internal class BleIdentityScanner(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val identityKeyForPeer: (String) -> BleIdentityKeyPayloadDto?,
    private val acceptedSequences: ConcurrentHashMap<String, Long>,
    private val supportsBleIdentity: () -> Boolean,
    private val radioPlan: (Boolean) -> RadioPowerPolicy.Plan,
    private val onVerifiedAdvertisement: (BleIdentityAdvertisement.Decoded, Int) -> Unit,
) {
    private val tag = "DiscoveryRepository"
    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private val callbacks = mutableListOf<ScanCallback>()

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val extended = BleRangeProbe.supportsExtendedAdvertising(context)
        val plan = radioPlan(extended)
        BleRangeProbe.logSummary(context)
        Log.i(tag, "Radio plan: ${RadioPowerPolicy.describe(plan)}")

        repeat(plan.scanPassCount) { pass ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let(::handleScanResult)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach(::handleScanResult)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(tag, "BLE scan pass $pass failed: $errorCode")
                }
            }
            val settings = BleRangePolicy.scanSettingsForPass(pass, extended, plan.scanEffort)
            try {
                scanner?.startScan(listOf(filter), settings, callback)
                callbacks.add(callback)
            } catch (error: Exception) {
                Log.e(tag, "Exception starting BLE scan pass $pass", error)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callbacks.forEach { callback ->
            try {
                scanner?.stopScan(callback)
            } catch (error: Exception) {
                Log.e(tag, "Exception stopping a BLE scan pass", error)
            }
        }
        callbacks.clear()
        scanner = null
    }

    fun resetSequence(peerId: String? = null) {
        if (peerId == null) acceptedSequences.clear() else acceptedSequences.remove(peerId)
    }

    private fun handleScanResult(result: ScanResult) {
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        if (serviceData.size != BleIdentityAdvertisement.TOTAL_BYTES) return

        var currentEvent = sessionManager.sessionState.value.currentEvent ?: return
        val hint = BleIdentityAdvertisement.decodeDiscoveryHint(serviceData)
        if (!sessionManager.sessionState.value.isHost && currentEvent.hostPeerId == null &&
            hint?.isHost == true && hint.eventCode == currentEvent.id
        ) {
            currentEvent = currentEvent.copy(hostPeerId = hint.peerId)
            sessionManager.updateEvent(currentEvent)
            (transport as? NearbyConnectionsManager)?.connectToPeer(hint.peerId)
        }
        if (!supportsBleIdentity()) return

        val peerId = BleIdentityAdvertisement.readPeerId(serviceData) ?: return
        val identityKey = identityKeyForPeer(peerId) ?: return
        val publicKey = BleIdentityCrypto.decodePublicKey(identityKey.publicKey) ?: return
        val decoded = BleIdentityAdvertisement.decode(serviceData, publicKey) ?: return
        if (decoded.eventCode != currentEvent.id || decoded.peerId != peerId) return
        if (peerId == sessionManager.peerId.value) return
        if ((transport as? NearbyConnectionsManager)?.hasLiveKeyControl(peerId) != true) return

        var accepted = false
        acceptedSequences.compute(peerId) { _, previous ->
            if (BleIdentityAdvertisement.acceptsSequence(previous, decoded.sequence)) {
                accepted = true
                decoded.sequence
            } else {
                previous
            }
        }
        if (!accepted || peerId.isEmpty()) return
        onVerifiedAdvertisement(decoded, result.rssi)
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fa1b-0000-1000-8000-00805f9b34fb")
    }
}
