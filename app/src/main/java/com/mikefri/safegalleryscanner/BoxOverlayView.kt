package com.mikefri.safegalleryscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BoxOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var detections: List<NudeNetDetector.Detection> = emptyList()
        set(value) { field = value; invalidate() }

    var imageWidth = 0
    var imageHeight = 0

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(5f, 2f, 2f, Color.RED)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || imageWidth == 0 || imageHeight == 0) return

        // meme calcul que fitCenter : zone reelle de l'image dans la vue
        val scale = Math.min(w / imageWidth, h / imageHeight)
        val dx = (w - imageWidth * scale) / 2f
        val dy = (h - imageHeight * scale) / 2f

        for (d in detections) {
            val r = RectF(
                dx + d.rect.left * imageWidth * scale,
                dy + d.rect.top * imageHeight * scale,
                dx + d.rect.right * imageWidth * scale,
                dy + d.rect.bottom * imageHeight * scale
            )
            canvas.drawRect(r, boxPaint)
            canvas.drawText(d.label + " " + "%.0f".format(d.score * 100) + "%", r.left + 4, Math.max(r.top - 8, 40f), textPaint)
        }
    }
}