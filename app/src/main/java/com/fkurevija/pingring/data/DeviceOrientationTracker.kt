package com.fkurevija.pingring.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which way the device is pointing, from the magnetometer and rotation-vector
 * sensors, and the world-space direction of every peer that has a bearing.
 *
 * The radar shows a peer as an arrow in world space, not in screen space, so a
 * peer off to the east stays east as the phone is turned: that needs the device
 * orientation (what this class fuses) applied to each peer's measured bearing.
 *
 * It is deliberately separate from the radio work that produces those bearings:
 * the sensors keep producing a heading while nothing is being discovered, and the
 * two have very different lifetimes - one is tied to the activity being visible,
 * the other to a PingRing running.
 */
class DeviceOrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _deviceHeading = MutableStateFlow(0f)
    val deviceHeading: StateFlow<Float> = _deviceHeading.asStateFlow()

    /**
     * Recomputes the world direction of every peer from the current device
     * orientation, called only when the orientation actually moved.
     */
    var onWorldVectorsChanged: (() -> Unit)? = null

    private val rotationMatrix = FloatArray(9).apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }
    private val geomagneticValues = FloatArray(3)
    private val gravityValues = FloatArray(3)
    private var hasRotationVector = false
    private var registered = false

    // The sensors fire far faster than the radar can usefully redraw, and each
    // update rewrites every peer, so the world vectors are recomputed at a rate a
    // display can follow. Throttled with a compare-and-set so two sensor events
    // arriving together cannot both pass the interval check.
    private val lastWorldVectorUpdate = AtomicLong(0L)

    private fun register() {
        if (registered) return
        val sm = sensorManager ?: return
        val rotVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVector != null) {
            sm.registerListener(this, rotVector, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback to mag field and accelerometer
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (mag != null) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        hasRotationVector = false
        registered = false
    }

    fun pause() = unregister()

    fun resume() = register()

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, ev.values)
                hasRotationVector = true
                publishOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(ev.values, 0, geomagneticValues, 0, 3)
                if (!hasRotationVector) computeRotationMatrixFallback()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(ev.values, 0, gravityValues, 0, 3)
                if (!hasRotationVector) computeRotationMatrixFallback()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeRotationMatrixFallback() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, geomagneticValues)) {
            publishOrientation()
        }
    }

    private fun publishOrientation() {
        publishHeading()
        publishWorldVectors()
    }

    private fun publishHeading() {
        val orientationValues = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        _deviceHeading.value = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
    }

    private fun publishWorldVectors() {
        val now = System.currentTimeMillis()
        val lastUpdate = lastWorldVectorUpdate.get()
        if (now - lastUpdate < WORLD_VECTOR_UPDATE_INTERVAL) return
        if (!lastWorldVectorUpdate.compareAndSet(lastUpdate, now)) return
        onWorldVectorsChanged?.invoke()
    }

    /**
     * Rotates a peer bearing from device space into world space.
     *
     * The bearing arrives in the device's own horizontal plane, where x is
     * sin(azimuth) and y is cos(azimuth), with z carrying the elevation. Applying
     * the rotation matrix turns that into a direction that means the same thing
     * no matter how the phone is held.
     */
    fun toWorldDirection(azimuthDegrees: Float?, elevationDegrees: Float?): Triple<Float, Float, Float> =
        worldDirection(rotationMatrix, azimuthDegrees, elevationDegrees)

    companion object {
        /** ~30 FPS: fast enough to look live, slow enough not to starve the UI thread. */
        private const val WORLD_VECTOR_UPDATE_INTERVAL = 33L

        /**
         * The same rotation as [toWorldDirection], with the matrix supplied rather
         * than read from a sensor.
         *
         * [rotationMatrix] is the 9-element row-major device-to-world rotation the
         * platform maintains from the rotation vector. An identity matrix means the
         * device is not rotated at all, so a peer at azimuth 0 (straight ahead)
         * points along +y and one at azimuth 90 (to the right) along +x.
         */
        fun worldDirection(
            rotationMatrix: FloatArray,
            azimuthDegrees: Float?,
            elevationDegrees: Float?,
        ): Triple<Float, Float, Float> {
            val azimuthRad = Math.toRadians((azimuthDegrees ?: 0f).toDouble()).toFloat()
            val elevationRad = Math.toRadians((elevationDegrees ?: 0f).toDouble()).toFloat()

            val deviceX = sin(azimuthRad) * cos(elevationRad)
            val deviceY = cos(azimuthRad) * cos(elevationRad)
            val deviceZ = sin(elevationRad)

            val worldX = rotationMatrix[0] * deviceX + rotationMatrix[1] * deviceY + rotationMatrix[2] * deviceZ
            val worldY = rotationMatrix[3] * deviceX + rotationMatrix[4] * deviceY + rotationMatrix[5] * deviceZ
            val worldZ = rotationMatrix[6] * deviceX + rotationMatrix[7] * deviceY + rotationMatrix[8] * deviceZ
            return Triple(worldX, worldY, worldZ)
        }
    }
}
