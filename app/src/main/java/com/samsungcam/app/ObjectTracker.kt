package com.samsungcam.app

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Graustufenbild (0..255), klein genug für Echtzeit-Tracking. */
class Gray(val w: Int, val h: Int, val d: IntArray) {
    companion object {
        /** Y-Plane (YUV_420) per Mittelwert auf ca. [targetW] Pixel Breite verkleinern. */
        fun fromY(buf: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, targetW: Int = 160): Gray {
            val f = max(1, srcW / targetW)
            val w = srcW / f; val h = srcH / f
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until f) for (dx in 0 until f)
                    sum += buf.get((y * f + dy) * rowStride + (x * f + dx)).toInt() and 255
                out[y * w + x] = sum / (f * f)
            }
            return Gray(w, h, out)
        }
    }
}

/** Bild um die Mitte im Uhrzeigersinn um [deg] drehen (Ränder werden mit dem Randpixel gefüllt). */
fun Gray.rotatedCw(deg: Float): Gray {
    if (kotlin.math.abs(deg) < 0.3f) return this
    val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
    val cx = (w - 1) / 2f; val cy = (h - 1) / 2f // Pixelmitten-Koordinaten
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val dx = x - cx; val dy = y - cy
        val sx = (cx + dx * c + dy * s).roundToInt().coerceIn(0, w - 1)
        val sy = (cy - dx * s + dy * c).roundToInt().coerceIn(0, h - 1)
        out[y * w + x] = d[sy * w + sx]
    }
    return Gray(w, h, out)
}

data class TrackResult(val l: Float, val t: Float, val r: Float, val b: Float, val found: Boolean)

/**
 * Leichter Objekt-Tracker für ein vom Nutzer gewähltes Objekt (Vorlagen-Vergleich mit
 * Helligkeits-Ausgleich, 3 Maßstäbe, Vorlage wird langsam nachgeführt). Wenn das Objekt verloren
 * geht, wird im ganzen Bild nach der Vorlage gesucht.
 * Koordinaten rein/raus: normiert 0..1 im Frame.
 */
class ObjectTracker {
    @Volatile var active = false; private set
    private var lostFrames = 0
    val isLost get() = lostFrames > 0

    private var cx = 0f; private var cy = 0f
    private var tpl = IntArray(0); private var tw = 0; private var th = 0
    private var anchor = IntArray(0); private var aw = 0; private var ah = 0

    fun clear() { active = false; lostFrames = 0 }

    fun start(g: Gray, l: Float, t: Float, r: Float, b: Float) {
        val minSide = 10
        val bw = max(minSide.toFloat(), (r - l) * g.w).coerceAtMost(g.w * 0.8f)
        val bh = max(minSide.toFloat(), (b - t) * g.h).coerceAtMost(g.h * 0.8f)
        tw = bw.roundToInt(); th = bh.roundToInt()
        cx = ((l + r) / 2f * g.w).coerceIn(tw / 2f, g.w - tw / 2f)
        cy = ((t + b) / 2f * g.h).coerceIn(th / 2f, g.h - th / 2f)
        tpl = patch(g, (cx - tw / 2f).roundToInt(), (cy - th / 2f).roundToInt(), tw, th)
        anchor = tpl.copyOf(); aw = tw; ah = th
        lostFrames = 0; active = true
    }

    fun update(g: Gray): TrackResult? {
        if (!active) return null
        val ii = integral(g)
        var best = Float.MAX_VALUE; var bx = 0; var by = 0; var bs = 1f

        if (lostFrames == 0) {
            val margin = (max(tw, th) * 0.9f).roundToInt() + 2
            for (s in floatArrayOf(1f, 0.92f, 1.08f)) {
                val w = (tw * s).roundToInt().coerceIn(8, g.w - 1); val h = (th * s).roundToInt().coerceIn(8, g.h - 1)
                val t = if (s == 1f) tpl else resize(tpl, tw, th, w, h)
                val tz = zero(t)
                val x0 = (cx - w / 2f).roundToInt(); val y0 = (cy - h / 2f).roundToInt()
                for (y in max(0, y0 - margin)..min(g.h - h, y0 + margin))
                    for (x in max(0, x0 - margin)..min(g.w - w, x0 + margin)) {
                        val sc = zsad(g, ii, x, y, w, h, tz, best)
                        if (sc < best) { best = sc; bx = x; by = y; bs = s }
                    }
            }
        } else { // verloren: im ganzen Bild suchen (grober Raster, dann nachschärfen)
            for ((t0, w0, h0) in listOf(Triple(tpl, tw, th), Triple(anchor, aw, ah))) {
                val tz = zero(t0)
                if (w0 >= g.w || h0 >= g.h) continue
                var y = 0
                while (y <= g.h - h0) {
                    var x = 0
                    while (x <= g.w - w0) {
                        val sc = zsad(g, ii, x, y, w0, h0, tz, best)
                        if (sc < best) { best = sc; bx = x; by = y; bs = w0.toFloat() / tw }
                        x += 2
                    }
                    y += 2
                }
            }
            // nachschärfen um den besten Treffer
            if (best < Float.MAX_VALUE) {
                val tz = zero(tpl); val ox = bx; val oy = by
                for (y in max(0, oy - 2)..min(g.h - th, oy + 2)) for (x in max(0, ox - 2)..min(g.w - tw, ox + 2)) {
                    val sc = zsad(g, ii, x, y, tw, th, tz, best)
                    if (sc < best) { best = sc; bx = x; by = y; bs = 1f }
                }
            }
        }

        val contrast = zero(tpl).let { z -> z.sumOf { abs(it).toDouble() }.toFloat() / z.size }
        val threshold = max(9f, 0.55f * contrast) * (if (lostFrames > 0) 0.8f else 1f)
        val found = best <= threshold
        if (found) {
            val w = (tw * bs).roundToInt().coerceIn(8, g.w - 1); val h = (th * bs).roundToInt().coerceIn(8, g.h - 1)
            val p = patch(g, bx, by, w, h)
            tpl = if (w == tw && h == th) IntArray(p.size) { (tpl[it] * 0.8f + p[it] * 0.2f).toInt() } else p
            tw = w; th = h
            cx = bx + w / 2f; cy = by + h / 2f
            lostFrames = 0
        } else lostFrames++
        return TrackResult((cx - tw / 2f) / g.w, (cy - th / 2f) / g.h, (cx + tw / 2f) / g.w, (cy + th / 2f) / g.h, found)
    }

    // --- Hilfsfunktionen ---

    private fun patch(g: Gray, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w)
            out[y * w + x] = g.d[(y0 + y).coerceIn(0, g.h - 1) * g.w + (x0 + x).coerceIn(0, g.w - 1)]
        return out
    }

    private fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw)
            out[y * dw + x] = src[min(sh - 1, y * sh / dh) * sw + min(sw - 1, x * sw / dw)]
        return out
    }

    private fun zero(t: IntArray): FloatArray {
        val m = t.sum().toFloat() / t.size
        return FloatArray(t.size) { t[it] - m }
    }

    private fun integral(g: Gray): LongArray {
        val ii = LongArray((g.w + 1) * (g.h + 1))
        for (y in 1..g.h) {
            var row = 0L
            for (x in 1..g.w) {
                row += g.d[(y - 1) * g.w + (x - 1)]
                ii[y * (g.w + 1) + x] = ii[(y - 1) * (g.w + 1) + x] + row
            }
        }
        return ii
    }

    /** Mittlere absolute Abweichung (Mittelwert abgezogen). Bricht früh ab, wenn schlechter als [best]. */
    private fun zsad(g: Gray, ii: LongArray, x0: Int, y0: Int, w: Int, h: Int, tz: FloatArray, best: Float): Float {
        val s = g.w + 1
        val sum = ii[(y0 + h) * s + x0 + w] - ii[y0 * s + x0 + w] - ii[(y0 + h) * s + x0] + ii[y0 * s + x0]
        val mean = sum.toFloat() / (w * h)
        val step = if (w > 40 || h > 40) 2 else 1
        val total = ((h + step - 1) / step) * ((w + step - 1) / step)
        val limit = best * total
        var acc = 0f
        var y = 0
        while (y < h) {
            val row = (y0 + y) * g.w + x0
            var x = 0
            while (x < w) { acc += abs(g.d[row + x] - mean - tz[y * w + x]); x += step }
            if (acc > limit) return Float.MAX_VALUE
            y += step
        }
        return acc / total
    }
}
