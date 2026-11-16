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
    val sensorW: Int,
    val sensorH: Int,
    private val maxZoom: Float = 5f,
    private val fill: Float = 0.45f,
    private val lostTimeoutMs: Long = 1500,
) {
    var follow = false
    var autoZoom = false
    var manualZoom = 1f

    /** Objekt-Tracking: Ausschnitt folgt immer, mit Vorhalt; zoomt heraus, wenn das Objekt verloren ist. */
    var trackingMode = false
    /** Super Steady: träger, ruhiger Schwenk (1 = normal, 2+ = sehr ruhig) und größere Totzone. */
    var steadiness = 1f
    var deadZone = 0.07f
    var trackMinZoom = 1.4f   // etwas Zoom, damit überhaupt Schwenk-Spielraum da ist
    var trackFill = 0.30f
    private var vx = 0f; private var vy = 0f
    private var lastSubject: Box? = null; private var lastSubjectMs = 0L

    var cx = sensorW / 2f; private set
    var cy = sensorH / 2f; private set
    var zoom = 1f; private set

    private var lastSeenMs = Long.MIN_VALUE
    private var lastUpdateMs = Long.MIN_VALUE

    fun update(subject: Box?, nowMs: Long): Crop {
        val dt = if (lastUpdateMs == Long.MIN_VALUE) 33L else (nowMs - lastUpdateMs).coerceIn(1, 200)
        lastUpdateMs = nowMs
        if (subject != null) {
            lastSubject?.let { p ->
                val d = (nowMs - lastSubjectMs).coerceAtLeast(1).toFloat()
                vx = vx * 0.7f + 0.3f * (subject.cx - p.cx) / d
                vy = vy * 0.7f + 0.3f * (subject.cy - p.cy) / d
            }
            lastSubject = subject; lastSubjectMs = nowMs
            lastSeenMs = nowMs
        }
        val timeout = if (trackingMode) 600L else lostTimeoutMs
        val lost = subject == null && (lastSeenMs == Long.MIN_VALUE || nowMs - lastSeenMs > timeout)
        val fillNow = if (trackingMode) trackFill else fill
        val minZ = if (trackingMode) trackMinZoom else 1f

        val targetZoom = when {
            !autoZoom -> manualZoom.coerceAtLeast(minZ)
            subject != null -> {
                val frac = max(subject.w / sensorW, subject.h / sensorH).coerceAtLeast(0.01f)
                (fillNow / frac).coerceIn(minZ, maxZoom)
            }
            lost -> 1f // Objekt weg: Blickfeld aufmachen, damit man es wiederfindet
            else -> zoom
        }.coerceIn(1f, maxZoom)

        val following = follow || trackingMode
        val (tx, ty) = when {
            !following -> sensorW / 2f to sensorH / 2f
            subject != null -> {
                if (trackingMode) {
                    // Totzone: kleine Bewegungen in der Bildmitte werden nicht nachgeregelt (ruhiges Bild)
                    val dzX = sensorW / zoom * deadZone; val dzY = sensorH / zoom * deadZone
                    val dx = subject.cx - cx; val dy = subject.cy - cy
                    val tx0 = if (kotlin.math.abs(dx) <= dzX) cx else subject.cx - dzX * kotlin.math.sign(dx)
                    val ty0 = if (kotlin.math.abs(dy) <= dzY) cy else subject.cy - dzY * kotlin.math.sign(dy)
                    tx0 to ty0
                } else subject.cx to subject.cy
            }
            lost && !trackingMode -> sensorW / 2f to sensorH / 2f
            else -> cx to cy
        }

        val panTau = (if (trackingMode) 140f else 250f) * steadiness
        zoom += (targetZoom - zoom) * smooth(dt, 450f)
        cx += (tx - cx) * smooth(dt, panTau)
        cy += (ty - cy) * smooth(dt, panTau)
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

    /** Aktueller Ausschnitt ohne Seiteneffekte (threadsicher genug für Frame-Zeitstempel). */
    fun cropNow(): Crop {
        val z = zoom
        val halfW = sensorW / z / 2f; val halfH = sensorH / z / 2f
        val x = cx.coerceIn(halfW, sensorW - halfW); val y = cy.coerceIn(halfH, sensorH - halfH)
        return Crop((x - halfW).toInt(), (y - halfH).toInt(), min(sensorW, (x + halfW).toInt()), min(sensorH, (y + halfH).toInt()))
    }

    /**
     * Rechnet eine Box aus dem *aufrechten* Analysebild (normiert 0..1, ML-Kit-Koordinaten)
     * in Sensor-Koordinaten um. [rot] = rotationDegrees des ImageProxy (Uhrzeigersinn,
     * um das Sensorbild aufrecht zu machen). Berücksichtigt den aktuellen Ausschnitt.
     */
    fun subjectFromUpright(l: Float, t: Float, r: Float, b: Float, rot: Int, at: Crop? = null): Box {
        val c = at ?: crop()
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
