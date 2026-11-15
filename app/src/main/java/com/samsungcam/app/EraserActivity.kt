package com.samsungcam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Objekt-Radierer: über das Objekt malen → „Entfernen“ → speichern. */
class EraserActivity : AppCompatActivity() {

    private lateinit var paintView: PaintView
    private lateinit var goBtn: Button
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        val bmp = decode(this, uri, 1600) ?: return finish()

        paintView = PaintView(this, bmp)
        goBtn = Button(this).apply { setText(R.string.erase_go) }
        val reset = Button(this).apply { setText(R.string.erase_clear) }
        val save = Button(this).apply { setText(R.string.erase_save) }
        val hint = TextView(this).apply { setText(R.string.erase_hint); setTextColor(Color.WHITE); setPadding(24, 24, 24, 8) }

        goBtn.setOnClickListener { runErase() }
        reset.setOnClickListener { paintView.resetAll(bmp) }
        save.setOnClickListener { save(paintView.image) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(reset, goBtn, save).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(hint)
            addView(paintView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        })
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun runErase() {
        val mask = paintView.buildMask()
        if (mask.none { it }) return
        val src = paintView.image
        goBtn.isEnabled = false
        worker.execute {
            val result = Inpainter.inpaint(src, mask)
            runOnUiThread { paintView.setResult(result); goBtn.isEnabled = true }
        }
    }

    private fun save(bmp: Bitmap) {
        worker.execute {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SCAM_ERASED_${System.currentTimeMillis()}.jpg")
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

    /** Zeigt das Bild (fit-center) und sammelt Pinselstriche in Bildkoordinaten. */
    private class PaintView(ctx: Context, bmp: Bitmap) : View(ctx) {
        var image: Bitmap = bmp; private set
        private var maskBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
        private var maskCanvas = Canvas(maskBmp)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        private val tint = Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(0x88FF0000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
        private val path = Path()
        private var scale = 1f; private var ox = 0f; private var oy = 0f

        private fun layout() {
            scale = min(width / image.width.toFloat(), height / image.height.toFloat())
            ox = (width - image.width * scale) / 2f; oy = (height - image.height * scale) / 2f
        }

        override fun onDraw(c: Canvas) {
            layout()
            c.save(); c.translate(ox, oy); c.scale(scale, scale)
            c.drawBitmap(image, 0f, 0f, null)
            c.drawBitmap(maskBmp, 0f, 0f, tint)
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = (e.x - ox) / scale; val y = (e.y - oy) / scale
            stroke.strokeWidth = 40f / scale
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { path.reset(); path.moveTo(x, y); path.lineTo(x + 0.1f, y) }
                MotionEvent.ACTION_MOVE -> path.lineTo(x, y)
                MotionEvent.ACTION_UP -> path.lineTo(x, y)
            }
            maskCanvas.drawPath(path, stroke)
            invalidate()
            return true
        }

        fun buildMask(): BooleanArray {
            val w = maskBmp.width; val h = maskBmp.height
            val a = IntArray(w * h)
            val argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(argb).drawBitmap(maskBmp, 0f, 0f, Paint().apply { color = Color.BLACK })
            argb.getPixels(a, 0, w, 0, 0, w, h)
            return BooleanArray(w * h) { (a[it] ushr 24) > 40 }
        }

        fun setResult(b: Bitmap) { image = b; clearMask() }
        fun resetAll(original: Bitmap) { image = original; clearMask() }
        private fun clearMask() {
            maskBmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ALPHA_8)
            maskCanvas = Canvas(maskBmp); path.reset(); invalidate()
        }
    }
}
