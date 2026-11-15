package com.samsungcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** Zeigt die Wasserwaage des Horizont-Locks (grün = gerade). */
class OverlayView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var showHorizon = false
        set(v) { field = v; invalidate() }
    var tiltDeg = 0f
        set(v) { field = v; if (showHorizon) invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }

    override fun onDraw(canvas: Canvas) {
        if (!showHorizon) return
        paint.color = if (abs(tiltDeg) < 0.7f) Color.GREEN else Color.YELLOW
        canvas.save()
        canvas.rotate(-tiltDeg, width / 2f, height / 2f)
        val y = height / 2f
        canvas.drawLine(width * 0.3f, y, width * 0.7f, y, paint)
        canvas.restore()
    }
}
