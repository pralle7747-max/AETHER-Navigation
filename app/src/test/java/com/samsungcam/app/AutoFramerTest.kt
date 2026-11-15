package com.samsungcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFramerTest {
    private val w = 4000
    private val h = 3000

    private fun run(f: AutoFramer, subject: Box?, frames: Int = 200) {
        var t = 0L
        repeat(frames) { t += 33; f.update(subject, t) }
    }

    @Test fun `ohne Modi bleibt der Zoom manuell und zentriert`() {
        val f = AutoFramer(w, h).apply { manualZoom = 2f }
        run(f, Box(100f, 100f, 300f, 300f))
        assertEquals(2f, f.zoom, 0.05f)
        assertEquals(w / 2f, f.cx, 1f)
    }

    @Test fun `follow schwenkt zum Motiv`() {
        val f = AutoFramer(w, h).apply { follow = true; manualZoom = 2f }
        run(f, Box(2900f, 1000f, 3100f, 1200f))
        assertTrue(f.cx > 2800f)
        assertTrue(f.crop().right <= w)
    }

    @Test fun `autozoom zoomt auf kleines Motiv und zurueck wenn es fehlt`() {
        val f = AutoFramer(w, h).apply { autoZoom = true }
        run(f, Box(1900f, 1400f, 2100f, 1600f)) // 5 % der Breite
        assertTrue(f.zoom > 3f)
        run(f, null, 300)
        assertEquals(1f, f.zoom, 0.1f)
    }

    @Test fun `Rotation 90 wird korrekt auf Sensor abgebildet`() {
        val f = AutoFramer(w, h)
        // oben links im aufrechten Bild -> bei rot=90 unten links... (x=v, y=1-u)
        val b = f.subjectFromUpright(0f, 0f, 0.1f, 0.1f, 90)
        assertEquals(0f, b.l, 1f); assertEquals(h * 0.9f, b.t, 1f)
        assertEquals(w * 0.1f, b.r, 1f); assertEquals(h.toFloat(), b.b, 1f)
    }
}

class InpainterTest {
    @Test fun `Loch in einfarbigem Bild wird mit derselben Farbe gefuellt`() {
        val w = 20; val h = 20
        val red = 0xFFFF0000.toInt()
        val px = IntArray(w * h) { red }
        val mask = BooleanArray(w * h) { (it % w) in 8..12 && (it / w) in 8..12 }
        for (i in mask.indices) if (mask[i]) px[i] = 0xFF000000.toInt()
        Inpainter.fillInto(px, w, h, mask)
        assertTrue(px.all { it == red })
    }

    @Test fun `dilate vergroessert die Maske`() {
        val w = 9; val h = 9
        val m = BooleanArray(w * h).also { it[4 * w + 4] = true }
        val d = Inpainter.dilate(m, w, h, 2)
        assertEquals(25, d.count { it })
    }
}
