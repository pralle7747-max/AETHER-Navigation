package com.samsungcam.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Klassisches Inpainting (kein generatives Modell): Die Maske wird von außen nach innen
 * Schicht für Schicht aus den bekannten Nachbarpixeln aufgefüllt und anschließend geglättet.
 * Gut für kleine Objekte vor ruhigem Hintergrund (Müll, Kabel, Passanten im Abstand).
 * Für große Flächen/Texturen wäre ein ML-Modell (z. B. LaMa als TFLite) der nächste Schritt.
 */
object Inpainter {

    fun inpaint(src: Bitmap, mask: BooleanArray, smoothPasses: Int = 6): Bitmap {
        val w = src.width; val h = src.height
        require(mask.size == w * h)
        var l = w; var t = h; var r = -1; var b = -1
        for (i in mask.indices) if (mask[i]) {
            val x = i % w; val y = i / w
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
        }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (r < 0) return out
        // Nur die Umgebung des Objekts bearbeiten (schneller, ausreichend Kontext)
        val m = 24
        val x0 = max(0, l - m); val y0 = max(0, t - m)
        val x1 = min(w - 1, r + m); val y1 = min(h - 1, b + m)
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        val px = IntArray(cw * ch).also { src.getPixels(it, 0, cw, x0, y0, cw, ch) }
        val cm = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) cm[y * cw + x] = mask[(y + y0) * w + (x + x0)]
        fillInto(px, cw, ch, cm, smoothPasses)
        out.setPixels(px, 0, cw, x0, y0, cw, ch)
        return out
    }

    /** Vergrößert die Maske um [r] Pixel (Quadrat), damit Objektränder mit entfernt werden. */
    fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask.copyOf()
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x])
            for (xx in max(0, x - r)..min(w - 1, x + r)) tmp[y * w + xx] = true
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (tmp[y * w + x])
            for (yy in max(0, y - r)..min(h - 1, y + r)) out[yy * w + x] = true
        return out
    }

    /** Pixel-Array-Variante (testbar ohne Android-Bitmap). */
    fun fillInto(px: IntArray, w: Int, h: Int, mask: BooleanArray, smoothPasses: Int = 6) {
        val original = px.copyOf()
        val known = BooleanArray(w * h) { !mask[it] }
        if (known.none { it }) return // nichts zum Abschauen vorhanden

        val queued = BooleanArray(w * h)
        var frontier = ArrayList<Int>()
        for (i in px.indices) if (mask[i] && hasKnownNeighbour(i, w, h, known)) { frontier.add(i); queued[i] = true }

        while (frontier.isNotEmpty()) {
            val colors = IntArray(frontier.size) { average(frontier[it], w, h, px, known) }
            for (k in frontier.indices) { px[frontier[k]] = colors[k]; known[frontier[k]] = true }
            val next = ArrayList<Int>()
            for (i in frontier) forEachNeighbour(i, w, h) { n ->
                if (!known[n] && !queued[n]) { queued[n] = true; next.add(n) }
            }
            frontier = next
        }

        // Nähte glätten: nur maskierte Pixel werden verändert
        repeat(smoothPasses) {
            val copy = px.copyOf()
            for (i in px.indices) if (mask[i]) {
                var r = 0; var g = 0; var b = 0; var n = 0
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) {
                        val c = copy[yy * w + xx]
                        r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
                    }
                }
                px[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        addGrain(px, w, h, mask, original)
    }

    /** Körnung wie in der Umgebung, damit die gefüllte Stelle nicht „plastikglatt“ wirkt. */
    private fun addGrain(px: IntArray, w: Int, h: Int, mask: BooleanArray, original: IntArray) {
        val ring = dilate(mask, w, h, 6)
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!ring[i] || mask[i]) continue
            var avg = 0
            for (dy in -1..1) for (dx in -1..1) avg += original[i + dy * w + dx] shr 8 and 255
            val d = (original[i] shr 8 and 255) - avg / 9.0
            sum += d; sum2 += d * d; n++
        }
        if (n < 20) return
        val sigma = Math.sqrt(max(0.0, sum2 / n - (sum / n) * (sum / n))) * 0.9
        if (sigma < 0.5) return
        val rnd = java.util.Random(1)
        for (i in px.indices) if (mask[i]) {
            val g = (rnd.nextGaussian() * sigma).toInt()
            val c = px[i]
            val r = ((c shr 16 and 255) + g).coerceIn(0, 255)
            val gg = ((c shr 8 and 255) + g).coerceIn(0, 255)
            val b = ((c and 255) + g).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
        }
    }

    private inline fun forEachNeighbour(i: Int, w: Int, h: Int, f: (Int) -> Unit) {
        val x = i % w; val y = i / w
        if (x > 0) f(i - 1)
        if (x < w - 1) f(i + 1)
        if (y > 0) f(i - w)
        if (y < h - 1) f(i + w)
    }

    private fun hasKnownNeighbour(i: Int, w: Int, h: Int, known: BooleanArray): Boolean {
        var found = false
        forEachNeighbour(i, w, h) { if (known[it]) found = true }
        return found
    }

    private fun ch(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    private fun average(i: Int, w: Int, h: Int, px: IntArray, known: BooleanArray): Int {
        val x = i % w; val y = i / w
        var r = 0f; var g = 0f; var b = 0f; var sum = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val xx = x + dx; val yy = y + dy
            if (xx !in 0 until w || yy !in 0 until h) continue
            val j = yy * w + xx
            if (!known[j]) continue
            val wt = 1f / (1 + dx * dx + dy * dy)
            val c = px[j]
            r += (c shr 16 and 255) * wt; g += (c shr 8 and 255) * wt; b += (c and 255) * wt; sum += wt
        }
        if (sum == 0f) return px[i]
        return (0xFF shl 24) or (ch(r / sum) shl 16) or (ch(g / sum) shl 8) or ch(b / sum)
    }
}
