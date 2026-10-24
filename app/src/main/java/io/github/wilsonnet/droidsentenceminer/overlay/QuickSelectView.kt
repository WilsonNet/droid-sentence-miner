package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bubble tap in [BubbleMode.QUICK]: the whole screen becomes a box cursor. Drag over a word and
 * lift to look it up; a tap without a drag cancels. [onSelect] gets the box as screen fractions.
 */
@SuppressLint("ViewConstructor")
class QuickSelectView(
    context: Context,
    private val onSelect: (RectF) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = Color.parseColor("#33000000") }
    private val boxPaint = Paint().apply { color = Color.parseColor("#264CD9B4") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CD9B4")
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val hintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B31B5E4A") }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var startX = 0f
    private var startY = 0f
    private val box = RectF()
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (dragging) {
            canvas.drawRect(box, boxPaint)
            canvas.drawRect(box, borderPaint)
            return
        }
        val text = HINT
        val y = 64 * density
        val half = hintPaint.measureText(text) / 2 + 16 * density
        canvas.drawRoundRect(width / 2f - half, y - 26 * density, width / 2f + half, y + 12 * density, 8 * density, 8 * density, hintBackground)
        canvas.drawText(text, width / 2f, y, hintPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = 8 * density
                if (!dragging && (abs(event.x - startX) > slop || abs(event.y - startY) > slop)) dragging = true
                if (dragging) {
                    box.set(min(startX, event.x), min(startY, event.y), max(startX, event.x), max(startY, event.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && box.width() >= MIN_BOX_DP * density && box.height() >= MIN_BOX_DP * density) {
                    onSelect(toScreenFraction(box))
                } else {
                    onCancel()
                }
            }
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    /** View px → fractions of the display (what capture crops use), wherever the window was placed. */
    private fun toScreenFraction(r: RectF): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val metrics = ScreenCaptureManager.screenMetrics(context)
        val w = metrics.widthPixels.toFloat().coerceAtLeast(1f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        return RectF(
            ((r.left + loc[0]) / w).coerceIn(0f, 1f),
            ((r.top + loc[1]) / h).coerceIn(0f, 1f),
            ((r.right + loc[0]) / w).coerceIn(0f, 1f),
            ((r.bottom + loc[1]) / h).coerceIn(0f, 1f),
        )
    }

    private companion object {
        const val HINT = "Drag over a word · tap to cancel"
        const val MIN_BOX_DP = 10
    }
}

/** What a tap on the bubble does; picked in the bubble menu (hold the bubble). */
enum class BubbleMode {
    /** Box a word, its definition pops up next to it; the rest of the box stays tappable. */
    QUICK,

    /** The region editor with Scan / Full screen / Live, results in the bottom panel. */
    REGION,
}
