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
