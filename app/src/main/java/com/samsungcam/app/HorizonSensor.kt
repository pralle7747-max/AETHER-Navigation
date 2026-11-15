package com.samsungcam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Liefert den Neigungswinkel (Roll) des Geräts im Hochformat in Grad.
 * Positiv = Gerät im Uhrzeigersinn gedreht. Außerhalb von ±45° (oder wenn das
 * Gerät flach liegt) wird 0 geliefert, dann gibt es keine Korrektur.
 */
class HorizonSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var angleDeg = 0f; private set
    var onChange: ((Float) -> Unit)? = null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]
        if (hypot(gx, gy) < 3f) return // Gerät liegt flach
        val raw = (atan2(-gx, gy) * 180.0 / PI).toFloat()
        val target = if (abs(raw) <= 45f) raw else 0f
        angleDeg += (target - angleDeg) * 0.25f // Tiefpass
        onChange?.invoke(angleDeg)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}
