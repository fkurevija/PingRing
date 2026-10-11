package com.fkurevija.pingring.data.discovery

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.fkurevija.pingring.data.BEACON_REPEAT_DURATION_UNTIL_STOPPED
import com.fkurevija.pingring.data.BeaconAlertPolicy
import com.fkurevija.pingring.data.BeaconAlertRepeat
import com.fkurevija.pingring.data.BeaconAlertSettings
import com.fkurevija.pingring.data.session.BeaconInfo
import com.fkurevija.pingring.data.chat.BeaconPayloadDto
import com.fkurevija.pingring.data.chat.BeaconVibrationPattern
import com.fkurevija.pingring.data.NearbyTransport
import com.fkurevija.pingring.data.session.SessionManager
import com.fkurevija.pingring.data.settings.AppearanceSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Owns beacon state, transport handling, alerts, and local beacon actions. */
internal class DiscoveryBeaconCoordinator(
    context: Context,
    private val sessionManager: SessionManager,
    private val transport: NearbyTransport,
    private val settingsRepository: AppearanceSettingsRepository,
    private val onPeerName: (String, String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val _activeBeacons = MutableStateFlow<Map<String, BeaconInfo>>(emptyMap())
    val activeBeacons: StateFlow<Map<String, BeaconInfo>> = _activeBeacons.asStateFlow()

    private val _endedBeacons = MutableStateFlow<List<BeaconInfo>>(emptyList())
    val endedBeacons: StateFlow<List<BeaconInfo>> = _endedBeacons.asStateFlow()

    private val _localBeacon = MutableStateFlow<BeaconInfo?>(null)
    val localBeacon: StateFlow<BeaconInfo?> = _localBeacon.asStateFlow()

    private val _alertSettings = MutableStateFlow(BeaconAlertSettings.DEFAULT)
    val alertSettings: StateFlow<BeaconAlertSettings> = _alertSettings.asStateFlow()

    private val _silencedBeaconIds = MutableStateFlow<Set<String>>(emptySet())
    val silencedBeaconIds: StateFlow<Set<String>> = _silencedBeaconIds.asStateFlow()

    private var stopJob: Job? = null

    init {
        scope.launch {
            settingsRepository.beaconAlertSettings.collect { _alertSettings.value = it }
        }
        scope.launch {
            settingsRepository.silencedBeaconIds.collect { _silencedBeaconIds.value = it }
        }
    }

    fun receive(dto: BeaconPayloadDto) {
        if (dto.senderPeerId == sessionManager.peerId.value) return
        val info = BeaconInfo(
            id = dto.id,
            senderPeerId = dto.senderPeerId,
            senderName = dto.senderName,
            title = dto.title,
            colorArgb = dto.colorArgb,
            vibrationPattern = dto.vibrationPattern,
            isActive = dto.isActive,
            timestamp = dto.timestamp,
        )
        if (dto.isActive) {
            onPeerName(dto.senderPeerId, dto.senderName)
            _activeBeacons.update { it + (dto.senderPeerId to info) }
            playAlert(dto.id, BeaconVibrationPattern.fromName(dto.vibrationPattern).pattern)
        } else {
            endBeacon(dto.senderPeerId)
        }
    }

    fun endBeacon(peerId: String) {
        val ending = _activeBeacons.value[peerId]
        _activeBeacons.update { it - peerId }
        if (ending == null) return
        stopVibration()
        _endedBeacons.update { history ->
            if (history.any { it.id == ending.id }) history
            else (history + ending.copy(isActive = false)).takeLast(HISTORY_LIMIT)
        }
    }

    fun renamePeer(peerId: String, name: String) {
        _activeBeacons.update { beacons ->
            beacons.mapValues { (senderPeerId, beacon) ->
                if (senderPeerId == peerId) beacon.copy(senderName = name) else beacon
            }
        }
        _endedBeacons.update { beacons ->
            beacons.map { beacon ->
                if (beacon.senderPeerId == peerId) beacon.copy(senderName = name) else beacon
            }
        }
        _localBeacon.update { beacon ->
            if (beacon?.senderPeerId == peerId) beacon.copy(senderName = name) else beacon
        }
    }

    fun playAlert(beaconId: String, pattern: LongArray) {
        if (!BeaconAlertPolicy.shouldAlert(beaconId, _silencedBeaconIds.value, pattern)) {
            stopVibration()
            return
        }
        val settings = _alertSettings.value
        val waveform = BeaconAlertPolicy.waveformFor(pattern, settings)
        if (waveform == null) {
            stopVibration()
            return
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(waveform.timings, waveform.repeatFromIndex))
            stopJob?.cancel()
            if (settings.repeat == BeaconAlertRepeat.REPEAT &&
                settings.repeatDurationMillis != BEACON_REPEAT_DURATION_UNTIL_STOPPED
            ) {
                stopJob = scope.launch {
                    delay(settings.repeatDurationMillis)
                    stopVibration()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Beacon alert playback failed", error)
        }
    }

    fun setSilenced(beaconId: String, silenced: Boolean) {
        val current = _silencedBeaconIds.value
        _silencedBeaconIds.value =
            if (silenced) current + beaconId else current - beaconId
        settingsRepository.setBeaconSilenced(beaconId, silenced)

        if (silenced) {
            stopVibration()
        } else {
            _activeBeacons.value.values.firstOrNull { it.id == beaconId }?.let { beacon ->
                playAlert(beacon.id, BeaconVibrationPattern.fromName(beacon.vibrationPattern).pattern)
            }
        }
    }

    fun setAlertSettings(settings: BeaconAlertSettings) {
        val normalized = settings.normalized()
        _alertSettings.value = normalized
        settingsRepository.setBeaconAlertSettings(normalized)
        if (normalized.repeat == BeaconAlertRepeat.ONCE) {
            stopVibration()
        } else {
            _activeBeacons.value.values.firstOrNull()?.let { active ->
                playAlert(active.id, BeaconVibrationPattern.fromName(active.vibrationPattern).pattern)
            }
        }
    }

    fun playTestPattern(pattern: LongArray) {
        if (pattern.isEmpty() || (pattern.size == 1 && pattern[0] == 0L)) return
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (error: Exception) {
            Log.w(TAG, "Vibration playback failed", error)
        }
    }

    fun stopVibration() {
        stopJob?.cancel()
        stopJob = null
        try {
            vibrator?.cancel()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to cancel vibration", error)
        }
    }

    fun activate(title: String, colorArgb: Long, vibrationPattern: String, senderName: String) {
        val localPeerId = sessionManager.peerId.value
        val dto = BeaconPayloadDto(
            id = UUID.randomUUID().toString(),
            senderPeerId = localPeerId,
            senderName = senderName,
            title = title,
            colorArgb = colorArgb,
            vibrationPattern = vibrationPattern,
            isActive = true,
            timestamp = System.currentTimeMillis(),
        )
        val info = BeaconInfo(
            id = dto.id,
            senderPeerId = dto.senderPeerId,
            senderName = dto.senderName,
            title = dto.title,
            colorArgb = dto.colorArgb,
            vibrationPattern = dto.vibrationPattern,
            isActive = true,
            timestamp = dto.timestamp,
        )
        _localBeacon.value = info
        _activeBeacons.update { it + (localPeerId to info) }
        playAlert(dto.id, BeaconVibrationPattern.fromName(vibrationPattern).pattern)
        transport.broadcastBeacon(dto)
    }

    fun cancelLocal() {
        val current = _localBeacon.value ?: return
        val dto = BeaconPayloadDto(
            id = current.id,
            senderPeerId = current.senderPeerId,
            senderName = current.senderName,
            title = current.title,
            colorArgb = current.colorArgb,
            vibrationPattern = current.vibrationPattern,
            isActive = false,
            timestamp = System.currentTimeMillis(),
        )
        _localBeacon.value = null
        endBeacon(current.senderPeerId)
        stopVibration()
        transport.broadcastBeacon(dto)
    }

    fun resetForNewEvent() {
        stopVibration()
        _activeBeacons.value = emptyMap()
        _endedBeacons.value = emptyList()
        _localBeacon.value = null
    }

    fun close() {
        resetForNewEvent()
        scope.cancel()
    }

    private companion object {
        const val HISTORY_LIMIT = 200
        const val TAG = "DiscoveryBeacon"
    }
}
