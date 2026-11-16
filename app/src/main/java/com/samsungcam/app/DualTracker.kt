package com.samsungcam.app

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.opencv.video.TrackerVit
import org.opencv.video.TrackerVit_Params
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Kleiner KI-Tracker (VitTrack, ~0,7 MB, Apache-2.0, OpenCV-DNN) + Vorlagen-Tracker als Rückfall.
 * - VitTrack folgt dem Objekt sehr genau (Maßstab, Verformung) und meldet einen Vertrauens-Score.
 * - Verliert VitTrack das Objekt, übernimmt der Vorlagen-Tracker (sucht im ganzen Bild) und setzt VitTrack neu.
 * - Fehlt OpenCV/Modell auf dem Gerät, läuft alles nur mit dem Vorlagen-Tracker.
 */
class DualTracker(private val ctx: Context) : FrameTracker {
    private val template = ObjectTracker()
    private var vit: TrackerVit? = null
    private var vitReady = false
    private var vitFailed = false
    private var loaded = false
    private var vitLost = 0
    private var lostNow = false

    /** true, wenn der KI-Tracker auf diesem Gerät läuft (für die Statusanzeige). */
    @Volatile var vitActive = false; private set

    override val frameWidth get() = 320
    override val active get() = template.active
    override val isLost get() = lostNow

    override fun start(g: Gray, l: Float, t: Float, r: Float, b: Float) {
        template.start(g.half(), l, t, r, b)
        lostNow = false; vitLost = 0
        initVit(g, l, t, r, b)
    }

    override fun clear() {
        template.clear(); vit = null; vitReady = false; vitActive = false; lostNow = false
    }

    override fun update(g: Gray): TrackResult? {
        val half = g.half()
        val t = template.update(half) ?: return null
        var out: TrackResult? = null

        if (vitReady) {
            val m = mat(g)
            val box = Rect()
            val ok = try { vit!!.update(m, box) } catch (e: Throwable) { Log.w(TAG, "VitTrack-Fehler", e); vitFailed = true; vitReady = false; false }
            m.release()
            if (vitReady) {
                val score = vit!!.getTrackingScore()
                if (ok && score >= 0.3f) {
                    vitLost = 0
                    val x0 = box.x / g.w.toFloat(); val y0 = box.y / g.h.toFloat()
                    out = TrackResult(x0.coerceIn(0f, 1f), y0.coerceIn(0f, 1f),
                        (x0 + box.width / g.w.toFloat()).coerceIn(0f, 1f), (y0 + box.height / g.h.toFloat()).coerceIn(0f, 1f), true)
                    // Vorlagen-Tracker nachziehen, wenn er woanders ist (hält den Rückfall bereit)
                    if (!t.found || overlap(t, out) < 0.4f) template.start(half, out.l, out.t, out.r, out.b)
                } else vitLost++
            }
        }
        if (out == null) {
            out = t
            // KI-Tracker neu setzen, sobald der Rückfall das Objekt wiedergefunden hat
            if (vitFailed.not() && t.found && (!vitReady || vitLost > 0) && (vitLost % 3 == 0)) initVit(g, t.l, t.t, t.r, t.b)
            if (!t.found && vitReady) vitLost = max(vitLost, 1)
        }
        lostNow = !out.found
        return out
    }

    // ---- Hilfen ----

    private fun overlap(a: TrackResult, b: TrackResult): Float {
        val iw = max(0f, min(a.r, b.r) - max(a.l, b.l)); val ih = max(0f, min(a.b, b.b) - max(a.t, b.t))
        val inter = iw * ih
        val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun mat(g: Gray): Mat {
        val bytes = ByteArray(g.w * g.h) { g.d[it].toByte() }
        val gm = Mat(g.h, g.w, CvType.CV_8UC1); gm.put(0, 0, bytes)
        val bgr = Mat(); Imgproc.cvtColor(gm, bgr, Imgproc.COLOR_GRAY2BGR); gm.release()
        return bgr
    }

    private fun ensureLoaded(): Boolean {
        if (vitFailed) return false
        if (loaded) return true
        return try {
            if (!OpenCVLoader.initLocal()) throw IllegalStateException("OpenCV konnte nicht geladen werden")
            val f = File(ctx.filesDir, "vittrack.onnx")
            if (!f.exists() || f.length() < 1000) ctx.assets.open("vittrack.onnx").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            loaded = true; true
        } catch (e: Throwable) { Log.w(TAG, "KI-Tracker nicht verfügbar – nutze Vorlagen-Tracker", e); vitFailed = true; false }
    }

    private fun initVit(g: Gray, l: Float, t: Float, r: Float, b: Float) {
        if (!ensureLoaded()) { vitActive = false; return }
        try {
            val p = TrackerVit_Params(); p.set_net(File(ctx.filesDir, "vittrack.onnx").absolutePath)
            val tr = TrackerVit.create(p)
            val x = (l * g.w).toInt().coerceIn(0, g.w - 9); val y = (t * g.h).toInt().coerceIn(0, g.h - 9)
            val w = ((r - l) * g.w).toInt().coerceIn(8, g.w - x); val h = ((b - t) * g.h).toInt().coerceIn(8, g.h - y)
            val m = mat(g); tr.init(m, Rect(x, y, w, h)); m.release()
            vit = tr; vitReady = true; vitActive = true; vitLost = 0
        } catch (e: Throwable) {
            Log.w(TAG, "VitTrack-Start fehlgeschlagen", e); vitFailed = true; vitReady = false; vitActive = false
        }
    }

    companion object { private const val TAG = "DualTracker" }
}
