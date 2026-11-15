package com.samsungcam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Liefert die Haltung des Geräts: [quadrant] (0 = Hochformat, 90/-90 = Querformat, 180 = kopfüber,
 * Uhrzeigersinn) und die Restneigung [angleDeg] (±45°, positiv = im Uhrzeigersinn geneigt).
 */
class HorizonSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var angleDeg = 0f; private set
    @Volatile var quadrant = 0; private set
    var onChange: ((angle: Float, quadrant: Int) -> Unit)? = null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]
        if (hypot(gx, gy) < 3f) return // Gerät liegt flach / zeigt nach oben oder unten
        val raw = (atan2(-gx, gy) * 180.0 / PI).toFloat()
        val (q, residual) = HorizonMath.decompose(raw, quadrant)
        if (q != quadrant) { quadrant = q; angleDeg = residual }
        else angleDeg = HorizonMath.smooth(angleDeg, residual)
        onChange?.invoke(angleDeg, quadrant)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}
