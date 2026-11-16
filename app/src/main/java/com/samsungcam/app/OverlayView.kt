package com.samsungcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** Overlay: Wasserwaage (Horizont-Lock), Zielrahmen, Lasso und Warnstreifen am Bildrand. */
class OverlayView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    /** true = liegt auf der (gedrehten) Bühne: nur Zielrahmen + Lasso. false = Bildschirm: Streifen + Wasserwaage. */
    var stage = false

    var showHorizon = false
        set(v) { field = v; invalidate() }
    var tiltDeg = 0f
        set(v) { field = v; if (showHorizon) invalidate() }

    /** Zielrahmen in View-Koordinaten (null = keiner). */
    var target: RectF? = null
        set(v) { field = v; invalidate() }
    var targetLost = false
        set(v) { field = v; invalidate() }
    /** Warnstufen L,T,R,B des *aufrechten* Bildes (0 ok, 1 gelb, 2 rot). */
    var warn = IntArray(4)
        set(v) { field = v; invalidate() }
    var lasso: Path? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val strip = Paint()
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(24f, 14f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        if (stage) {
            lasso?.let { canvas.drawPath(it, lassoPaint) }
            target?.let {
                box.color = if (targetLost) Color.RED else Color.rgb(0, 230, 118)
                canvas.drawRoundRect(it, 12f, 12f, box)
            }
            return
        }
        drawStrips(canvas)
        if (showHorizon) {
            line.color = if (abs(tiltDeg) < 0.7f) Color.GREEN else Color.YELLOW
            canvas.save()
            canvas.rotate(-tiltDeg, width / 2f, height / 2f)
            canvas.drawLine(width * 0.3f, height / 2f, width * 0.7f, height / 2f, line)
            canvas.restore()
        }
    }

    private fun drawStrips(canvas: Canvas) {
        val t = 64f * density
        val levels = if (targetLost) IntArray(4) { 2 } else warn
        for (side in 0 until 4) {
            val lv = levels[side]
            if (lv == 0) continue
            val col = if (lv == 2) Color.argb(230, 244, 67, 54) else Color.argb(200, 255, 193, 7)
            val clear = Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))
            val w = width.toFloat(); val h = height.toFloat()
            when (side) {
                EdgeWarning.LEFT -> { strip.shader = LinearGradient(0f, 0f, t, 0f, col, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, t, h, strip) }
                EdgeWarning.RIGHT -> { strip.shader = LinearGradient(w, 0f, w - t, 0f, col, clear, Shader.TileMode.CLAMP); canvas.drawRect(w - t, 0f, w, h, strip) }
                EdgeWarning.TOP -> { strip.shader = LinearGradient(0f, 0f, 0f, t, col, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, t, strip) }
                else -> { strip.shader = LinearGradient(0f, h, 0f, h - t, col, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, h - t, w, h, strip) }
            }
        }
    }
}
