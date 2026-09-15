package com.shware.flashpill.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 侧边栏把手（M2 交互）：
 *
 * - 按下 → onPressStart（立即开始录音，短按会丢弃）
 * - 按住 ≥250ms 松手 → onLongPressEnd（保存录音）
 * - 短按（<250ms）松手 → onTap（打开列表）
 * - 按住后拖动 → onDragStart（取消录音）→ onDrag(rawY) → onDragEnd
 * - 手势被系统取消 → onCancel（丢弃录音）
 */
@SuppressLint("ViewConstructor")
class SidebarHandleView(context: Context) : View(context) {

    var onPressStart: (() -> Unit)? = null
    var onLongPressEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
    }
    private val recordingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF6B6B")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }

    @Volatile
    private var recording = false

    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private val touchSlop = context.resources.displayMetrics.density * 8f

    /** 录音状态下的视觉反馈（把手变红） */
    fun setRecording(value: Boolean) {
        if (recording == value) return
        recording = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w / 2f
        canvas.drawRoundRect(
            0f, 0f, w, h, radius, radius,
            if (recording) recordingPaint else bodyPaint
        )

        val cx = w / 2f
        val cy = h / 2f
        val dotRadius = w * 0.16f
        for (i in -1..1) {
            canvas.drawCircle(cx, cy + i * (w * 0.9f), dotRadius, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                downTime = System.currentTimeMillis()
                dragging = false
                onPressStart?.invoke()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.rawY - downY) > touchSlop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) onDrag?.invoke(event.rawY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val heldMs = System.currentTimeMillis() - downTime
                if (dragging) {
                    onDragEnd?.invoke()
                } else if (heldMs >= LONG_PRESS_MS) {
                    performClick()
                    onLongPressEnd?.invoke()
                } else {
                    performClick()
                    onTap?.invoke()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd?.invoke() else onCancel?.invoke()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 250L
    }
}