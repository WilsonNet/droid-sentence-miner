package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating circular bubble. Drawn entirely in code: a magnifier glyph with a subtle shadow.
 * Tap -> onTap, drag -> onMove, long press -> onLongPress.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onMove: (Float, Float) -> Unit,
    private val onLongPress: () -> Unit,
    private val onRelease: () -> Unit,
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B5E4A")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F5F0")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }
        },
    )

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(56).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f - dp(4)
        canvas.drawCircle(cx, cy + dp(1.5f), radius + dp(1), shadowPaint)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val r = radius * 0.42f
        canvas.drawCircle(cx - radius * 0.12f, cy - radius * 0.12f, r, glyphPaint)
        val handle = r * 0.95f
        canvas.drawLine(
            cx - radius * 0.12f + handle,
            cy - radius * 0.12f + handle,
            cx + radius * 0.42f,
            cy + radius * 0.42f,
            glyphPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!dragging && hypot(dx, dy) > dp(8)) dragging = true
                if (dragging) {
                    onMove(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onRelease()
                dragging = false
            }
        }
        return true
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    @Suppress("unused")
    private fun absDp(value: Float): Float = abs(value)
}
