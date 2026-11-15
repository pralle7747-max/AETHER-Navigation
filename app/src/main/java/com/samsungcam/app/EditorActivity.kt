package com.samsungcam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Foto-Editor mit den "S-Handy"-Funktionen:
 *  - Radieren: Objekt übermalen → wird entfernt
 *  - Verschieben: Objekt umkreisen → ausschneiden, mit dem Finger verschieben → anwenden
 *  - Malen: freihändig zeichnen (Farben, Pinselgröße)
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var view: EditorView
    private lateinit var hint: TextView
    private lateinit var action: Button
    private lateinit var colors: LinearLayout
    private lateinit var selectRow: LinearLayout
    private lateinit var selectBtn: Button
    private lateinit var clearBtn: Button
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        val bmp = decode(this, uri, 2000) ?: return finish()
        view = EditorView(this, bmp)

        hint = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24, 16, 24, 8) }
        action = Button(this)

        fun toolButton(label: String, t: EditorView.Tool) =
            Button(this).apply { text = label; setOnClickListener { selectTool(t) } }
        val tools = LinearLayout(this).apply {
            listOf(
                toolButton("Radieren", EditorView.Tool.ERASE),
                toolButton("Verschieben", EditorView.Tool.MOVE),
                toolButton("Malen", EditorView.Tool.DRAW),
            ).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }

        colors = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.WHITE, Color.BLACK)
                .forEach { c ->
                    addView(View(context).apply {
                        setBackgroundColor(c)
                        setOnClickListener { view.brushColor = c }
                    }, LinearLayout.LayoutParams(88, 88).apply { setMargins(8, 8, 8, 8) })
                }
        }

        selectBtn = Button(this).apply {
            text = "Auswahl: Smart (Tippen)"
            setOnClickListener {
                view.smartMode = !view.smartMode
                text = if (view.smartMode) "Auswahl: Smart (Tippen)" else "Auswahl: Pinsel"
            }
        }
        clearBtn = Button(this).apply { text = "Auswahl leeren"; setOnClickListener { view.clearSelection() } }
        selectRow = LinearLayout(this).apply {
            addView(selectBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val undo = Button(this).apply { text = "Zurück"; setOnClickListener { view.undo() } }
        val save = Button(this).apply { text = "Speichern"; setOnClickListener { save() } }
        action.setOnClickListener { runAction() }
        val bar = LinearLayout(this).apply {
            listOf(undo, action, save).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(tools); addView(hint); addView(selectRow); addView(colors)
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        })
        selectTool(EditorView.Tool.ERASE)
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun selectTool(t: EditorView.Tool) {
        view.commitFloating()
        view.tool = t
        colors.visibility = if (t == EditorView.Tool.DRAW) View.VISIBLE else View.GONE
        selectRow.visibility = if (t == EditorView.Tool.DRAW) View.GONE else View.VISIBLE
        action.isEnabled = t != EditorView.Tool.DRAW
        when (t) {
            EditorView.Tool.ERASE -> { action.text = "Entfernen"; hint.text = "Auf das Objekt tippen (Smart) oder mit dem Pinsel übermalen, dann „Entfernen“. Mehrfach tippen = Auswahl erweitern." }
            EditorView.Tool.MOVE -> { action.text = "Ausschneiden"; hint.text = "Objekt antippen (Smart) oder umfahren → „Ausschneiden“ → verschieben → „Anwenden“." }
            EditorView.Tool.DRAW -> hint.text = "Mit dem Finger zeichnen. Farbe oben wählen."
        }
    }

    private fun runAction() {
        when (view.tool) {
            EditorView.Tool.ERASE -> background { view.erase() }
            EditorView.Tool.MOVE ->
                if (view.hasFloating) { view.commitFloating(); action.text = "Ausschneiden" }
                else background { if (view.cutOut()) runOnUiThread { action.text = "Anwenden" } }
            EditorView.Tool.DRAW -> Unit
        }
    }

    private fun background(job: () -> Unit) {
        action.isEnabled = false
        worker.execute { job(); runOnUiThread { action.isEnabled = true } }
    }

    private fun save() {
        view.commitFloating()
        val bmp = view.image
        worker.execute {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SCAM_EDIT_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SamsungCam2")
            }
            val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            val ok = out != null && contentResolver.openOutputStream(out)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } == true
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    class EditorView(ctx: Context, start: Bitmap) : View(ctx) {
        enum class Tool { ERASE, MOVE, DRAW }

        var tool = Tool.ERASE
        var brushColor = Color.RED
        var image: Bitmap = start.copy(Bitmap.Config.ARGB_8888, true); private set

        private var maskBmp = newMask()
        private var maskCanvas = Canvas(maskBmp)
        private val undoStack = ArrayDeque<Bitmap>()
        private val path = Path()
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val tint = Paint().apply { colorFilter = PorterDuffColorFilter(0x88FF0000.toInt(), PorterDuff.Mode.SRC_IN) }

        private var floating: Bitmap? = null
        private var floatX = 0f; private var floatY = 0f   // Position in Bildkoordinaten
        private var lastX = 0f; private var lastY = 0f
        val hasFloating get() = floating != null

        private var scale = 1f; private var ox = 0f; private var oy = 0f

        private fun newMask() = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ALPHA_8)

        private fun layout() {
            scale = min(width / image.width.toFloat(), height / image.height.toFloat())
            ox = (width - image.width * scale) / 2f; oy = (height - image.height * scale) / 2f
        }

        override fun onDraw(c: Canvas) {
            layout()
            c.save(); c.translate(ox, oy); c.scale(scale, scale)
            c.drawBitmap(image, 0f, 0f, null)
            floating?.let { c.drawBitmap(it, floatX, floatY, null) }
            c.drawBitmap(maskBmp, 0f, 0f, tint)
            c.restore()
        }

        var smartMode = true
        private var downX = 0f; private var downY = 0f; private var moved = false
        private val slop = 24f

        private val segmenter by lazy {
            SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder()
                    .enableMultipleSubjects(
                        SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build()
                    ).build()
            )
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = (e.x - ox) / scale; val y = (e.y - oy) / scale
            if (floating != null) { // Objekt verschieben
                if (e.action == MotionEvent.ACTION_MOVE) { floatX += x - lastX; floatY += y - lastY; invalidate() }
                lastX = x; lastY = y
                return true
            }
            val isDraw = tool == Tool.DRAW
            val paint = if (isDraw) drawPaint else maskPaint
            paint.strokeWidth = 40f / scale
            if (isDraw) drawPaint.color = brushColor
            val target = if (isDraw) Canvas(image) else maskCanvas
            val smartTap = !isDraw && smartMode

            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    if (isDraw) pushUndo()
                    path.reset(); path.moveTo(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) {
                        moved = true; path.lineTo(x, y)
                    } else if (moved) path.lineTo(x, y)
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) path.lineTo(x, y)
                    else if (smartTap) { tapSelect(x.toInt(), y.toInt()); return true }
                    else path.lineTo(x + 0.1f, y)
                }
            }
            if (moved || e.action == MotionEvent.ACTION_UP) target.drawPath(path, paint)
            invalidate()
            return true
        }

        /** Smart Select: erst KI (Vordergrund-Objekt unter dem Finger), sonst Farbfläche. */
        private fun tapSelect(ix: Int, iy: Int) {
            if (ix !in 0 until image.width || iy !in 0 until image.height) return
            val snapshot = image.copy(Bitmap.Config.ARGB_8888, false)
            fun wand() {
                val w = snapshot.width; val h = snapshot.height
                val px = IntArray(w * h).also { snapshot.getPixels(it, 0, w, 0, 0, w, h) }
                addSelection(FloodSelect.select(px, w, h, ix, iy))
            }
            try {
                segmenter.process(InputImage.fromBitmap(snapshot, 0))
                    .addOnSuccessListener { res ->
                        val w = snapshot.width
                        val hit = res.subjects.firstOrNull { s ->
                            val lx = ix - s.startX; val ly = iy - s.startY
                            lx in 0 until s.width && ly in 0 until s.height &&
                                (s.confidenceMask?.get(ly * s.width + lx) ?: 0f) > 0.5f
                        }
                        if (hit == null) wand() else {
                            val sel = BooleanArray(w * snapshot.height)
                            val cm = hit.confidenceMask
                            if (cm == null) wand() else {
                                for (yy in 0 until hit.height) for (xx in 0 until hit.width) {
                                    if (cm.get(yy * hit.width + xx) > 0.5f) {
                                        val gx = hit.startX + xx; val gy = hit.startY + yy
                                        if (gx in 0 until w && gy in 0 until snapshot.height) sel[gy * w + gx] = true
                                    }
                                }
                                addSelection(sel)
                            }
                        }
                    }
                    .addOnFailureListener { wand() } // z. B. Modell noch nicht geladen / kein Play Services
            } catch (e: Exception) { wand() }
        }

        private fun addSelection(sel: BooleanArray) {
            val w = image.width; val h = image.height
            val colors = IntArray(w * h) { if (sel[it]) 0xFF000000.toInt() else 0 }
            maskCanvas.drawBitmap(Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888), 0f, 0f, null)
            postInvalidate()
        }

        fun clearSelection() { clearMask(); invalidate() }

        private fun pushUndo() {
            undoStack.addLast(image.copy(Bitmap.Config.ARGB_8888, false))
            if (undoStack.size > 8) undoStack.removeFirst()
        }

        fun undo() {
            floating = null
            val prev = undoStack.removeLastOrNull() ?: return
            image = prev.copy(Bitmap.Config.ARGB_8888, true)
            clearMask(); invalidate()
        }

        private fun clearMask() { maskBmp = newMask(); maskCanvas = Canvas(maskBmp); path.reset() }

        private fun readMask(): BooleanArray {
            val w = image.width; val h = image.height
            val argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(argb).drawBitmap(maskBmp, 0f, 0f, Paint().apply { color = Color.BLACK })
            val a = IntArray(w * h); argb.getPixels(a, 0, w, 0, 0, w, h)
            return BooleanArray(w * h) { (a[it] ushr 24) > 40 }
        }

        /** Hintergrund-Thread. Entfernt das markierte Objekt. */
        fun erase() {
            val mask = readMask()
            if (mask.none { it }) return
            post { pushUndo() }
            val result = Inpainter.inpaint(image, Inpainter.dilate(mask, image.width, image.height, 5))
            post { image = result.copy(Bitmap.Config.ARGB_8888, true); clearMask(); invalidate() }
        }

        /** Hintergrund-Thread. Schneidet das markierte Objekt aus und lässt es schweben. */
        fun cutOut(): Boolean {
            val w = image.width; val h = image.height
            val mask = readMask()
            var l = w; var t = h; var r = -1; var b = -1
            for (i in mask.indices) if (mask[i]) {
                val x = i % w; val y = i / w
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
            if (r < 0) return false
            val px = IntArray(w * h); image.getPixels(px, 0, w, 0, 0, w, h)
            val cut = IntArray((r - l + 1) * (b - t + 1))
            for (y in t..b) for (x in l..r) {
                cut[(y - t) * (r - l + 1) + (x - l)] = if (mask[y * w + x]) px[y * w + x] else 0
            }
            val cutBmp = Bitmap.createBitmap(cut, r - l + 1, b - t + 1, Bitmap.Config.ARGB_8888)
            val base = Inpainter.inpaint(image, Inpainter.dilate(mask, w, h, 4))
            post {
                pushUndo()
                image = base.copy(Bitmap.Config.ARGB_8888, true)
                floating = cutBmp; floatX = l.toFloat(); floatY = t.toFloat()
                clearMask(); invalidate()
            }
            return true
        }

        fun commitFloating() {
            val f = floating ?: return
            Canvas(image).drawBitmap(f, floatX, floatY, null)
            floating = null
            invalidate()
        }
    }
}
