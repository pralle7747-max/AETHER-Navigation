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

class HorizonMathTest {
    @Test fun `Hochformat leicht geneigt`() {
        val (q, r) = HorizonMath.decompose(12f, 0)
        assertEquals(0, q); assertEquals(12f, r, 0.01f)
    }
    @Test fun `Querformat wird als Haltung 90 erkannt mit Restneigung`() {
        val (q, r) = HorizonMath.decompose(97f, 0)
        assertEquals(90, q); assertEquals(7f, r, 0.01f)
    }
    @Test fun `Hysterese verhindert Flattern an der 45 Grad Grenze`() {
        assertEquals(0, HorizonMath.decompose(48f, 0).first)
        assertEquals(90, HorizonMath.decompose(52f, 0).first)
    }
    @Test fun `kopfueber ist 180 und links gedreht -90`() {
        assertEquals(180, HorizonMath.decompose(-178f, 0).first)
        assertEquals(-90, HorizonMath.decompose(-85f, 0).first)
    }
    @Test fun `Glaettung rastet bei null ein und folgt grossen Bewegungen schnell`() {
        assertEquals(0f, HorizonMath.smooth(0.3f, 0.2f), 0f)
        assertTrue(HorizonMath.smooth(0f, 30f) > 10f)
    }
}

class ResolutionTiersTest {
    private val sizes = listOf(4000 to 3000, 4032 to 3024, 8160 to 6120, 4000 to 2250)
    @Test fun `waehlt echte Groessen`() {
        assertEquals(4032 to 3024, ResolutionTiers.pick(sizes, 12))
        assertEquals(8160 to 6120, ResolutionTiers.pick(sizes, 50))
    }
    @Test fun `100 MP fehlt wenn das Geraet es nicht anbietet`() {
        assertEquals(null, ResolutionTiers.pick(sizes, 100))
        assertEquals(12000 to 9000, ResolutionTiers.pick(sizes + (12000 to 9000), 100))
    }
}

class FloodSelectTest {
    @Test fun `waehlt nur die zusammenhaengende Flaeche`() {
        val w = 30; val h = 30
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 10..19) for (x in 10..19) px[y * w + x] = 0xFF2040C0.toInt()
        val sel = FloodSelect.select(px, w, h, 15, 15)
        assertEquals(100, sel.count { it })
        assertTrue(sel[15 * w + 15]); assertTrue(!sel[0])
    }
}

class GeometryTest {
    @Test fun `Rotation hin und zurueck ist identisch`() {
        for (rot in listOf(0, 90, 180, 270)) {
            val (x, y) = ViewMap.uprightToFrame(0.2f, 0.7f, rot)
            val (u, v) = ViewMap.frameToUpright(x, y, rot)
            assertEquals(0.2f, u, 1e-5f); assertEquals(0.7f, v, 1e-5f)
        }
    }
    @Test fun `View Mitte ist Bildmitte und Ansicht rechnet zurueck`() {
        val (u, v) = ViewMap.viewToUpright(540f, 1200f, 1080f, 2400f, 3f, 4f)
        assertEquals(0.5f, u, 1e-4f); assertEquals(0.5f, v, 1e-4f)
        val (x, y) = ViewMap.uprightToView(0.3f, 0.6f, 1080f, 2400f, 3f, 4f)
        val (u2, v2) = ViewMap.viewToUpright(x, y, 1080f, 2400f, 3f, 4f)
        assertEquals(0.3f, u2, 1e-4f); assertEquals(0.6f, v2, 1e-4f)
    }
    @Test fun `Warnstufen gelb und rot an der richtigen Seite`() {
        val w = 4000; val h = 3000
        val l = EdgeWarning.levels(Box(300f, 1000f, 900f, 1500f), w, h) // links 7,5 % -> gelb
        assertEquals(1, l[EdgeWarning.LEFT]); assertEquals(0, l[EdgeWarning.RIGHT])
        val r = EdgeWarning.levels(Box(3900f, 1000f, 3990f, 1500f), w, h) // rechts 0,25 % -> rot
        assertEquals(2, r[EdgeWarning.RIGHT])
    }
    @Test fun `Sensor links ist bei Rotation 90 oben im aufrechten Bild`() {
        val up = EdgeWarning.toUpright(intArrayOf(2, 0, 0, 0), 90)
        assertEquals(2, up[EdgeWarning.TOP])
    }
    @Test fun `Tracking Modus zoomt mindestens und zoomt bei Verlust heraus`() {
        val f = AutoFramer(4000, 3000).apply { trackingMode = true; autoZoom = true }
        var t = 0L
        repeat(150) { t += 33; f.update(Box(1900f, 1400f, 2100f, 1600f), t) }
        assertTrue(f.zoom >= 1.4f)
        assertEquals(2000f, f.cx, 40f)
        repeat(200) { t += 33; f.update(null, t) }
        assertTrue(f.zoom < 1.2f)
    }
}

class ObjectTrackerTest {
    private val w = 160; private val h = 120
    private val rnd = java.util.Random(7)
    private val bg = IntArray(w * h) { rnd.nextInt(256) }
    private val obj = IntArray(24 * 24) { rnd.nextInt(256) }

    private fun frame(ox: Int, oy: Int, withObject: Boolean = true): Gray {
        val d = bg.copyOf()
        if (withObject) for (y in 0 until 24) for (x in 0 until 24) d[(oy + y) * w + ox + x] = obj[y * 24 + x]
        return Gray(w, h, d)
    }

    @Test fun `folgt einem bewegten Objekt`() {
        val tr = ObjectTracker()
        tr.start(frame(40, 40), 40f / w, 40f / h, 64f / w, 64f / h)
        var x = 40
        repeat(25) { x += 3
            val res = tr.update(frame(x, 40))!!
            assertTrue(res.found)
            assertEquals(x / w.toFloat(), res.l, 2f / w)
        }
    }

    @Test fun `meldet Verlust und findet das Objekt wieder`() {
        val tr = ObjectTracker()
        tr.start(frame(40, 40), 40f / w, 40f / h, 64f / w, 64f / h)
        repeat(5) { tr.update(frame(40, 40, withObject = false)) }
        assertTrue(tr.isLost)
        val res = tr.update(frame(100, 70))!!
        assertTrue(res.found)
        assertEquals(100 / w.toFloat(), res.l, 3f / w)
    }
}

class LensLabelTest {
    @Test fun `A56 typische Linsen werden richtig benannt`() {
        assertEquals("0,5× Ultraweit", LensLabel.of(false, 0.54f, 12f))
        assertEquals("1× Haupt", LensLabel.of(false, 1f, 50f))
        assertEquals("Makro?", LensLabel.of(false, 1f, 5f))
        assertEquals("Front", LensLabel.of(true, 1f, 12f))
        assertEquals("3× Tele", LensLabel.of(false, 3f, 10f))
    }
    @Test fun `Kleinbild Aequivalent`() {
        assertEquals(24f, LensLabel.equiv35(6f, 9f), 0.01f)
    }
}
