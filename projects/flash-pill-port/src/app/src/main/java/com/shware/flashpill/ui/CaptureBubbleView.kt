package com.shware.flashpill.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Locale

/**
 * 录音气泡：状态文字 + 实时波形（数据来自 WavRecorder.onAmplitude）。
 */
class CaptureBubbleView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6000000")
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B6B")
        strokeWidth = context.resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.resources.displayMetrics.scaledDensity * 14f
    }

    private val amplitudes = ArrayDeque<Float>()
    private val maxBars = 48
    private var startedAt = System.currentTimeMillis()
    private val rect = RectF()

    fun reset() {
        amplitudes.clear()
        startedAt = System.currentTimeMillis()
        invalidate()
    }

    fun pushAmplitude(amp: Int) {
        val normalized = (amp / 32768f).coerceIn(0f, 1f)
        amplitudes.addLast(normalized)
        while (amplitudes.size > maxBars) amplitudes.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 状态文字
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        val label = String.format(Locale.US, "录音中 %d:%02d", elapsed / 60, elapsed % 60)
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, h * 0.7f, textY, textPaint)

        // 波形（右侧区域）
        val left = w * 0.42f
        val right = w - h * 0.6f
        val mid = h / 2f
        if (amplitudes.isNotEmpty() && right > left) {
            val step = (right - left) / maxBars
            var x = left
            for (a in amplitudes) {
                val barH = (h * 0.12f) + a * (h * 0.6f)
                canvas.drawLine(x, mid - barH / 2f, x, mid + barH / 2f, wavePaint)
                x += step
            }
        }

        // 录音期间持续刷新（用于计时/波形）
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}