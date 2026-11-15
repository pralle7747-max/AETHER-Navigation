package com.samsungcam.app

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Rechteck in Sensor-Koordinaten (Pixel des aktiven Sensorbereichs). */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
}

data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Reine Logik (ohne Android-Abhängigkeiten) für „Follow“ und „Auto-Zoom“.
 * Berechnet pro Frame den Ausschnitt (SCALER_CROP_REGION) in Sensor-Koordinaten.
 *
 *  - follow:   Ausschnitt schwenkt weich zum Motiv
 *  - autoZoom: Zoom wird so gewählt, dass das Motiv ~[fill] des Bildes füllt
 *  - beide aus: nur manueller Zoom (Pinch), zentriert
 */
class AutoFramer(
    private val sensorW: Int,
    private val sensorH: Int,
    private val maxZoom: Float = 5f,
    private val fill: Float = 0.45f,
    private val lostTimeoutMs: Long = 1500,
) {
    var follow = false
    var autoZoom = false
    var manualZoom = 1f

    var cx = sensorW / 2f; private set
    var cy = sensorH / 2f; private set
    var zoom = 1f; private set

    private var lastSeenMs = Long.MIN_VALUE
    private var lastUpdateMs = Long.MIN_VALUE

    fun update(subject: Box?, nowMs: Long): Crop {
        val dt = if (lastUpdateMs == Long.MIN_VALUE) 33L else (nowMs - lastUpdateMs).coerceIn(1, 200)
        lastUpdateMs = nowMs
        if (subject != null) lastSeenMs = nowMs
        val lost = subject == null && (lastSeenMs == Long.MIN_VALUE || nowMs - lastSeenMs > lostTimeoutMs)

        val targetZoom = when {
            !autoZoom -> manualZoom
            subject != null -> {
                val frac = max(subject.w / sensorW, subject.h / sensorH).coerceAtLeast(0.01f)
                (fill / frac).coerceIn(1f, maxZoom)
            }
            lost -> 1f
            else -> zoom
        }.coerceIn(1f, maxZoom)

        val (tx, ty) = when {
            !follow -> sensorW / 2f to sensorH / 2f
            subject != null -> subject.cx to subject.cy
            lost -> sensorW / 2f to sensorH / 2f
            else -> cx to cy
        }

        zoom += (targetZoom - zoom) * smooth(dt, 450f)
        cx += (tx - cx) * smooth(dt, 250f)
        cy += (ty - cy) * smooth(dt, 250f)
        return crop()
    }

    fun crop(): Crop {
        val halfW = sensorW / zoom / 2f
        val halfH = sensorH / zoom / 2f
        cx = cx.coerceIn(halfW, sensorW - halfW)
        cy = cy.coerceIn(halfH, sensorH - halfH)
        return Crop(
            (cx - halfW).toInt(), (cy - halfH).toInt(),
            min(sensorW, (cx + halfW).toInt()), min(sensorH, (cy + halfH).toInt()),
        )
    }

    /**
     * Rechnet eine Box aus dem *aufrechten* Analysebild (normiert 0..1, ML-Kit-Koordinaten)
     * in Sensor-Koordinaten um. [rot] = rotationDegrees des ImageProxy (Uhrzeigersinn,
     * um das Sensorbild aufrecht zu machen). Berücksichtigt den aktuellen Ausschnitt.
     */
    fun subjectFromUpright(l: Float, t: Float, r: Float, b: Float, rot: Int): Box {
        val c = crop()
        val cw = (c.right - c.left).toFloat()
        val ch = (c.bottom - c.top).toFloat()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((u, v) in listOf(l to t, r to t, l to b, r to b)) {
            val (x, y) = when (((rot % 360) + 360) % 360) {
                90 -> v to (1f - u)
                180 -> (1f - u) to (1f - v)
                270 -> (1f - v) to u
                else -> u to v
            }
            val sx = c.left + x * cw
            val sy = c.top + y * ch
            minX = min(minX, sx); maxX = max(maxX, sx)
            minY = min(minY, sy); maxY = max(maxY, sy)
        }
        return Box(minX, minY, maxX, maxY)
    }

    private fun smooth(dtMs: Long, tauMs: Float) = 1f - exp(-dtMs / tauMs)
}
