package com.samsungcam.app

import kotlin.math.abs

/** „Zauberstab“: wählt die zusammenhängende Fläche mit ähnlicher Farbe wie der Tippunkt. */
object FloodSelect {
    fun select(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Int = 30): BooleanArray {
        val out = BooleanArray(w * h)
        if (sx !in 0 until w || sy !in 0 until h) return out
        // Referenzfarbe = Mittel um den Tippunkt (robust gegen Rauschen)
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val x = sx + dx; val y = sy + dy
            if (x in 0 until w && y in 0 until h) {
                val c = px[y * w + x]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++
            }
        }
        val rr = sr / n; val rg = sg / n; val rb = sb / n
        val limit = tolerance * 3
        fun similar(c: Int) =
            abs((c shr 16 and 255) - rr) + abs((c shr 8 and 255) - rg) + abs((c and 255) - rb) <= limit

        val stack = IntArray(w * h); var top = 0
        val start = sy * w + sx
        if (!similar(px[start])) { out[start] = true; return out } // wenigstens der Tippunkt
        stack[top++] = start; out[start] = true
        while (top > 0) {
            val i = stack[--top]
            val x = i % w; val y = i / w
            if (x > 0 && !out[i - 1] && similar(px[i - 1])) { out[i - 1] = true; stack[top++] = i - 1 }
            if (x < w - 1 && !out[i + 1] && similar(px[i + 1])) { out[i + 1] = true; stack[top++] = i + 1 }
            if (y > 0 && !out[i - w] && similar(px[i - w])) { out[i - w] = true; stack[top++] = i - w }
            if (y < h - 1 && !out[i + w] && similar(px[i + w])) { out[i + w] = true; stack[top++] = i + w }
        }
        return out
    }
}
