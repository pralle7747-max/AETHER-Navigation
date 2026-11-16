package com.samsungcam.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Reine Mathe für den Horizont-Lock (ohne Android-Abhängigkeit, testbar). */
object HorizonMath {
    /** Winkel nach (-180, 180]. */
    fun normalize(a: Float): Float {
        var v = a % 360f
        if (v > 180f) v -= 360f
        if (v <= -180f) v += 360f
        return v
    }

    /**
     * Zerlegt den Rohwinkel (Uhrzeigersinn, 0 = Hochformat aufrecht) in die nächste
     * 90°-Haltung (-90, 0, 90, 180) und die Restneigung (±45°).
     * Hysterese: Die Haltung wechselt erst ab 50° Abweichung, damit sie nicht flattert.
     */
    fun decompose(raw: Float, currentQuadrant: Int): Pair<Int, Float> {
        val diff = normalize(raw - currentQuadrant)
        if (abs(diff) <= 50f) return currentQuadrant to diff
        val q = normalize((raw / 90f).roundToInt() * 90f).roundToInt()
        return q to normalize(raw - q)
    }

    /** Adaptive Glättung: ruhig bei kleinen Zittern, schnell bei echten Bewegungen; rastet bei 0° ein. */
    fun smooth(prev: Float, target: Float, minAlpha: Float = 0.04f): Float {
        val d = target - prev
        val alpha = (minAlpha + abs(d) / 15f).coerceIn(minAlpha, 0.7f)
        val v = prev + d * alpha
        return if (abs(v) < 0.4f && abs(target) < 0.6f) 0f else v
    }
}
