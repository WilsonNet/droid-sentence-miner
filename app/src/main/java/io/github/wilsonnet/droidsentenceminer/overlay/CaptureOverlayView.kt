package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wilsonnet.droidsentenceminer.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen region selector shown before a scan.
 * The region can be moved, resized with corner handles, or redrawn from scratch.
 */
@SuppressLint("ViewConstructor")
class CaptureOverlayView(
    context: Context,
    initial: RectF,
    private val onScan: (RectF) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val region = RectF(initial)
    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CD9B4")
        style = Paint.Style.STROKE
        strokeWidth = dp(2)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CD9B4")
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startRegion = RectF()
    private val hint: TextView

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, CREATE }

    init {
        setWillNotDraw(false)
        isClickable = true

        hint = TextView(context).apply {
            text = context.getString(R.string.ov_capture_hint)
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#B31B5E4A"))
            setPadding(dp(16).toInt(), dp(10).toInt(), dp(16).toInt(), dp(10).toInt())
        }
        addView(
            hint,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(48).toInt()
            },
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10).toInt(), dp(10).toInt(), dp(10).toInt(), dp(24).toInt())
            setBackgroundColor(Color.parseColor("#B3101C18"))
        }
        buttons.addView(weightedButton(context.getString(R.string.ov_action_scan), primary = true) {
            onScan(regionFraction())
        })
        buttons.addView(weightedButton(context.getString(R.string.ov_action_full), primary = false) {
            region.set(0f, 0f, width.toFloat(), height.toFloat())
            invalidate()
            onScan(regionFraction())
        })
        buttons.addView(weightedButton(context.getString(android.R.string.cancel), primary = false) {
            onCancel()
        })
        addView(
            buttons,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )
    }

    private fun weightedButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 15f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.parseColor("#0B201A") else Color.WHITE)
            setBackgroundColor(if (primary) Color.parseColor("#4CD9B4") else Color.parseColor("#33FFFFFF"))
            setPadding(dp(6).toInt(), dp(12).toInt(), dp(6).toInt(), dp(12).toInt())
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6).toInt()
                marginEnd = dp(6).toInt()
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), region.top, dimPaint)
        canvas.drawRect(0f, region.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, region.top, region.left, region.bottom, dimPaint)
        canvas.drawRect(region.right, region.top, width.toFloat(), region.bottom, dimPaint)

        canvas.drawRect(region, borderPaint)
        val size = dp(14)
        listOf(
            region.left to region.top,
            region.right to region.top,
            region.left to region.bottom,
            region.right to region.bottom,
        ).forEach { (x, y) ->
            val rect = RectF(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
            canvas.drawRect(rect, handlePaint)
            canvas.drawRect(rect, handleBorderPaint)
        }
        super.dispatchDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                startRegion.set(region)
                mode = when {
                    near(x, y, region.left, region.top) -> Mode.RESIZE_TL
                    near(x, y, region.right, region.top) -> Mode.RESIZE_TR
                    near(x, y, region.left, region.bottom) -> Mode.RESIZE_BL
                    near(x, y, region.right, region.bottom) -> Mode.RESIZE_BR
                    region.contains(x, y) -> Mode.MOVE
                    else -> Mode.CREATE
                }
                if (mode == Mode.CREATE) {
                    region.set(x, y, x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.MOVE -> {
                        val dx = x - downX
                        val dy = y - downY
                        region.set(startRegion)
                        region.offset(dx, dy)
                        clampRegion()
                    }
                    Mode.RESIZE_TL -> {
                        region.left = min(x, region.right - minSize())
                        region.top = min(y, region.bottom - minSize())
                        clampRegion()
                    }
                    Mode.RESIZE_TR -> {
                        region.right = max(x, region.left + minSize())
                        region.top = min(y, region.bottom - minSize())
                        clampRegion()
                    }
                    Mode.RESIZE_BL -> {
                        region.left = min(x, region.right - minSize())
                        region.bottom = max(y, region.top + minSize())
                        clampRegion()
                    }
                    Mode.RESIZE_BR -> {
                        region.right = max(x, region.left + minSize())
                        region.bottom = max(y, region.top + minSize())
                        clampRegion()
                    }
                    Mode.CREATE -> {
                        region.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    }
                    Mode.NONE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.CREATE && (region.width() < minSize() || region.height() < minSize())) {
                    // treat as a tap: open a scanner-friendly default region under the finger
                    val w = width * 0.7f
                    val h = height * 0.14f
                    region.set(
                        (x - w / 2).coerceIn(0f, width - w),
                        (y - h / 2).coerceIn(0f, height - h),
                        (x - w / 2).coerceIn(0f, width - w) + w,
                        (y - h / 2).coerceIn(0f, height - h) + h,
                    )
                }
                mode = Mode.NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun regionFraction(): RectF {
        if (width == 0 || height == 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            region.left / width,
            region.top / height,
            region.right / width,
            region.bottom / height,
        )
    }

    private fun clampRegion() {
        region.left = region.left.coerceIn(0f, width.toFloat())
        region.top = region.top.coerceIn(0f, height.toFloat())
        region.right = region.right.coerceIn(0f, width.toFloat())
        region.bottom = region.bottom.coerceIn(0f, height.toFloat())
        if (region.width() < minSize()) region.right = min(width.toFloat(), region.left + minSize())
        if (region.height() < minSize()) region.bottom = min(height.toFloat(), region.top + minSize())
    }

    private fun near(x: Float, y: Float, targetX: Float, targetY: Float): Boolean =
        abs(x - targetX) <= dp(28) && abs(y - targetY) <= dp(28)

    private fun minSize(): Float = dp(48)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
