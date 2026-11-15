package com.samsungcam.app

import android.graphics.Bitmap

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
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        fillInto(px, w, h, mask, smoothPasses)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pixel-Array-Variante (testbar ohne Android-Bitmap). */
    fun fillInto(px: IntArray, w: Int, h: Int, mask: BooleanArray, smoothPasses: Int = 6) {
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
        return (0xFF shl 24) or ((r / sum).toInt() shl 16) or ((g / sum).toInt() shl 8) or (b / sum).toInt()
    }
}
