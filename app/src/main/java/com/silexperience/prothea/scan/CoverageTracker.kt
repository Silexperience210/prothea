package com.silexperience.prothea.scan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Suit la couverture angulaire du scan : un anneau de [sectors] positions
 * autour de la patiente. Source : rotation vector sensor (fonctionne sur
 * tout telephone, sans ARCore).
 */
class CoverageTracker(
    context: Context,
    val sectors: Int = 16
) : SensorEventListener {

    var listener: (Float) -> Unit = {}

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var referenceAzimuth: Float? = null
    val covered = BooleanArray(sectors)

    val progress: Float
        get() = covered.count { it }.toFloat() / sectors

    fun start() {
        rotationSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Appeler quand la patiente est cadree pour definir l'angle zero. */
    fun calibrate(currentAzimuth: Float) {
        referenceAzimuth = currentAzimuth
    }

    fun markSectorFor(azimuth: Float) {
        val ref = referenceAzimuth ?: return
        var rel = (azimuth - ref) % (2f * PI.toFloat())
        if (rel < 0) rel += 2f * PI.toFloat()
        val idx = (rel / (2f * PI.toFloat()) * sectors).roundToInt() % sectors
        covered[idx] = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val ori = FloatArray(3)
        SensorManager.getOrientation(rot, ori)
        listener(ori[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun azimuthDeg(a: Float): Double =
            (Math.toDegrees(a.toDouble()) + 360.0) % 360.0
    }
}
