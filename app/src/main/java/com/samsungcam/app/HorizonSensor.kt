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
 * Haltung des Geräts: [quadrant] (0 = Hochformat, 90/-90 = Querformat, 180 = kopfüber, Uhrzeigersinn),
 * Restneigung [angleDeg] (±45°, positiv = im Uhrzeigersinn) und der volle Rollwinkel [rollDeg].
 *
 * Bevorzugt den Gyro-gestützten Rotationsvektor (schnell und ruhig, auch bei Vibration auf Rad/RC-Car);
 * ohne ihn der Schwerkraftsensor mit stärkerer Glättung.
 */
class HorizonSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fused = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val sensor = fused
        ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val minAlpha = if (fused != null) 0.35f else 0.06f
    private val rot = FloatArray(9)

    @Volatile var angleDeg = 0f; private set
    @Volatile var quadrant = 0; private set
    /** Voller Rollwinkel (Quadrant + Restneigung), für Tracker und Bildausgleich. */
    val rollDeg: Float get() = quadrant + angleDeg
    var onChange: ((angle: Float, quadrant: Int) -> Unit)? = null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // "Oben" im Geräte-Koordinatensystem (entspricht der Schwerkraftmessung)
        val ux: Float; val uy: Float; val limit: Float
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            ux = rot[6]; uy = rot[7]; limit = 0.3f
        } else { ux = e.values[0]; uy = e.values[1]; limit = 3f }
        if (hypot(ux, uy) < limit) return // Gerät liegt flach / zeigt nach oben oder unten
        val raw = (atan2(-ux, uy) * 180.0 / PI).toFloat()
        val (q, residual) = HorizonMath.decompose(raw, quadrant)
        if (q != quadrant) { quadrant = q; angleDeg = residual }
        else angleDeg = HorizonMath.smooth(angleDeg, residual, minAlpha)
        onChange?.invoke(angleDeg, quadrant)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}
