package com.samsungcam.app

import kotlin.math.max

/**
 * Koordinaten-Umrechnungen zwischen Ansicht (Screen), aufrechtem Bild und Sensor-/Analyse-Frame.
 * Alle Funktionen sind rein (testbar). [rot] = rotationDegrees des ImageProxy.
 */
object ViewMap {
    /** View-Punkt -> aufrechtes Bild, normiert 0..1. PreviewView-Standard FILL_CENTER (Bild füllt, Ränder abgeschnitten). */
    fun viewToUpright(x: Float, y: Float, viewW: Float, viewH: Float, imgW: Float, imgH: Float): Pair<Float, Float> {
        val s = max(viewW / imgW, viewH / imgH)
        return ((x - viewW / 2f) / s + imgW / 2f) / imgW to ((y - viewH / 2f) / s + imgH / 2f) / imgH
    }

    fun uprightToView(u: Float, v: Float, viewW: Float, viewH: Float, imgW: Float, imgH: Float): Pair<Float, Float> {
        val s = max(viewW / imgW, viewH / imgH)
        return viewW / 2f + (u - 0.5f) * imgW * s to viewH / 2f + (v - 0.5f) * imgH * s
    }

    /** Aufrechtes Bild (u,v) -> Sensor-/Frame-Orientierung (x,y), beide 0..1. */
    fun uprightToFrame(u: Float, v: Float, rot: Int): Pair<Float, Float> = when (((rot % 360) + 360) % 360) {
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }

    /** Umkehrung von [uprightToFrame]. */
    fun frameToUpright(x: Float, y: Float, rot: Int): Pair<Float, Float> = when (((rot % 360) + 360) % 360) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }
}

/** Warnstufen pro Seite: 0 = ok, 1 = gelb (nähert sich dem Rand), 2 = rot (fast draußen). */
object EdgeWarning {
    const val LEFT = 0; const val TOP = 1; const val RIGHT = 2; const val BOTTOM = 3

    /** Abstand des Objekts zum Rand des *gesamten* Kamerabilds (nicht nur des Ausschnitts), Reihenfolge L,T,R,B im Sensor-Frame. */
    fun levels(box: Box, sensorW: Int, sensorH: Int, yellow: Float = 0.12f, red: Float = 0.04f): IntArray {
        val d = floatArrayOf(box.l / sensorW, box.t / sensorH, (sensorW - box.r) / sensorW, (sensorH - box.b) / sensorH)
        return IntArray(4) { if (d[it] < red) 2 else if (d[it] < yellow) 1 else 0 }
    }

    /** Sensor-Seiten (L,T,R,B) -> Seiten des aufrechten Bildes (im Uhrzeigersinn um [rot] weitergedreht). */
    fun toUpright(levels: IntArray, rot: Int): IntArray {
        val shift = (((rot % 360) + 360) % 360) / 90
        val out = IntArray(4)
        for (i in 0 until 4) out[(i + shift) % 4] = levels[i]
        return out
    }
}
