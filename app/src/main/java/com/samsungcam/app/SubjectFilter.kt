package com.samsungcam.app

import kotlin.math.max
import kotlin.math.min

/** Kalman-Filter (Position + Geschwindigkeit) für eine Achse. Einheiten beliebig, aber konstant. */
class Kalman1D(var x: Double, private val accelStd: Double) {
    var v = 0.0; private set
    private var p00 = 1e-2; private var p01 = 0.0; private var p11 = 1.0

    fun predict(dt: Double) {
        x += v * dt
        val q2 = accelStd * accelStd
        val dt2 = dt * dt
        p00 += 2 * dt * p01 + dt2 * p11 + q2 * dt2 * dt2 / 4
        p01 += dt * p11 + q2 * dt2 * dt / 2
        p11 += q2 * dt2
    }

    fun update(z: Double, measStd: Double) {
        val s = p00 + measStd * measStd
        val k0 = p00 / s; val k1 = p01 / s
        val y = z - x
        x += k0 * y; v += k1 * y
        val a = p00; val b = p01; val d = p11
        p00 = a - a * a / s; p01 = b - a * b / s; p11 = d - b * b / s
    }

    fun reset(x0: Double) { x = x0; v = 0.0; p00 = 1e-2; p01 = 0.0; p11 = 1.0 }
}

/**
 * Glättet die Objekt-Box (Sensor-Pixel): Kalman auf der Mitte (mit Geschwindigkeit, überbrückt Lücken
 * zwischen KI-Erkennungen), Größe per Tiefpass. Alles in Einheiten der Sensorbreite.
 */
class SubjectFilter(private val sensorW: Int, private val sensorH: Int, accelStd: Double = 1.2) {
    private val kx = Kalman1D(0.5, accelStd)
    private val ky = Kalman1D(0.5 * sensorH / sensorW, accelStd)
    private var w = 0.1; private var h = 0.1
    private var lastMs = Long.MIN_VALUE
    var initialized = false; private set

    fun reset(b: Box, nowMs: Long) {
        kx.reset(b.cx / sensorW.toDouble()); ky.reset(b.cy / sensorW.toDouble())
        w = b.w / sensorW.toDouble(); h = b.h / sensorW.toDouble()
        lastMs = nowMs; initialized = true
    }

    fun predict(nowMs: Long) {
        if (!initialized) return
        val dt = ((nowMs - lastMs).coerceIn(0, 300)) / 1000.0
        lastMs = nowMs
        if (dt > 0) { kx.predict(dt); ky.predict(dt) }
    }

    /** [std] = Messrauschen (Einheiten der Sensorbreite): KI-Erkennung klein (≈0,008), Vorlage größer (≈0,02). */
    fun update(b: Box, std: Double) {
        if (!initialized) return
        kx.update(b.cx / sensorW.toDouble(), std); ky.update(b.cy / sensorW.toDouble(), std)
        w += (b.w / sensorW - w) * 0.3; h += (b.h / sensorW - h) * 0.3
    }

    fun box(): Box {
        val cx = (kx.x * sensorW).toFloat(); val cy = (ky.x * sensorW).toFloat()
        val bw = (w * sensorW).toFloat(); val bh = (h * sensorW).toFloat()
        return Box(
            (cx - bw / 2).coerceIn(0f, sensorW.toFloat()), (cy - bh / 2).coerceIn(0f, sensorH.toFloat()),
            (cx + bw / 2).coerceIn(0f, sensorW.toFloat()), (cy + bh / 2).coerceIn(0f, sensorH.toFloat()))
    }
}

fun iou(a: Box, b: Box): Float {
    val iw = max(0f, min(a.r, b.r) - max(a.l, b.l)); val ih = max(0f, min(a.b, b.b) - max(a.t, b.t))
    val inter = iw * ih
    val union = a.w * a.h + b.w * b.h - inter
    return if (union <= 0f) 0f else inter / union
}

/** Deutsche Namen für die häufigsten COCO-Klassen des KI-Modells. */
object Labels {
    private val de = mapOf(
        "person" to "Person", "bicycle" to "Fahrrad", "car" to "Auto", "motorcycle" to "Motorrad",
        "airplane" to "Flugzeug", "bus" to "Bus", "train" to "Zug", "truck" to "Lkw", "boat" to "Boot",
        "bird" to "Vogel", "cat" to "Katze", "dog" to "Hund", "horse" to "Pferd", "sheep" to "Schaf",
        "cow" to "Kuh", "skateboard" to "Skateboard", "surfboard" to "Surfbrett", "sports ball" to "Ball",
        "kite" to "Drache", "frisbee" to "Frisbee", "skis" to "Ski", "snowboard" to "Snowboard",
    )
    fun german(label: String?): String = if (label == null) "Objekt" else de[label.lowercase()] ?: label
}
