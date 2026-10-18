package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wilsonnet.droidsentenceminer.capture.TextArea
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen editor for auto OCR text areas, drawn directly over the running game.
 * Drag on empty space to add a box, drag a box to move it, drag a corner to resize,
 * tap the × to delete. The toolbar sits at the top (dialogue boxes and subtitles are
 * usually at the bottom) and can be flipped down with ⇅.
 */
@SuppressLint("ViewConstructor")
class AreaSelectorView(
    context: Context,
    initial: List<TextArea>,
    orientationLabel: String,
    private val onSave: (List<TextArea>) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val boxes = mutableListOf<RectF>()
    private var pendingInitial: List<TextArea>? = initial
    private var selected: Int = -1

    private val dimPaint = Paint().apply { color = Color.parseColor("#80000000") }
    private val dimPath = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CD9B4")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val selectedPaint = Paint(borderPaint).apply { strokeWidth = dp(3f); color = Color.parseColor("#FFD54F") }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54F") }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val deleteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, CREATE }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private val startBox = RectF()
    private val toolbar: LinearLayout
    private var toolbarAtTop = true

    init {
        setWillNotDraw(false)
        isClickable = true

        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101C18"))
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
        }
        toolbar.addView(TextView(context).apply {
            text = "Draw boxes over the game's text ($orientationLabel). Drag to move, corners to resize, × to delete."
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6f).toInt(), 0, 0)
        }
        row.addView(button("Save", primary = true) { onSave(fractions()) })
        row.addView(button("Clear", primary = false) {
            boxes.clear()
            selected = -1
            invalidate()
        })
        row.addView(button("⇅", primary = false, weight = 0.5f) { flipToolbar() })
        row.addView(button("Cancel", primary = false) { onCancel() })
        toolbar.addView(row)
        addView(toolbar, toolbarParams())
    }

    private fun toolbarParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        gravity = if (toolbarAtTop) Gravity.TOP else Gravity.BOTTOM
        if (toolbarAtTop) topMargin = dp(24f).toInt() else bottomMargin = dp(16f).toInt()
    }

    private fun flipToolbar() {
        toolbarAtTop = !toolbarAtTop
        toolbar.layoutParams = toolbarParams()
    }

    private fun button(label: String, primary: Boolean, weight: Float = 1f, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        maxLines = 1
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#0B201A") else Color.WHITE)
        setBackgroundColor(if (primary) Color.parseColor("#4CD9B4") else Color.parseColor("#33FFFFFF"))
        setPadding(dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = dp(4f).toInt()
            marginEnd = dp(4f).toInt()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pendingInitial?.let { areas ->
            boxes.clear()
            areas.forEach { boxes += RectF(it.left * w, it.top * h, it.right * w, it.bottom * h) }
            pendingInitial = null
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Dim everything except the boxes (even-odd fill punches them out).
        dimPath.reset()
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
        boxes.forEach { dimPath.addRect(it, android.graphics.Path.Direction.CW) }
        canvas.drawPath(dimPath, dimPaint)
        boxes.forEachIndexed { i, box ->
            canvas.drawRect(box, if (i == selected) selectedPaint else borderPaint)
            canvas.drawText("${i + 1}", box.left + dp(4f), box.top + dp(14f), labelText)
            if (i == selected) {
                corners(box).forEach { (x, y) -> canvas.drawCircle(x, y, dp(8f), handlePaint) }
            }
            val (dx, dy) = deleteCenter(box)
            canvas.drawCircle(dx, dy, dp(12f), deletePaint)
            canvas.drawText("×", dx, dy + dp(5.5f), deleteText)
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
                val deleteHit = boxes.indexOfFirst { near(x, y, deleteCenter(it).first, deleteCenter(it).second, 16f) }
                if (deleteHit >= 0) {
                    boxes.removeAt(deleteHit)
                    selected = -1
                    mode = Mode.NONE
                    invalidate()
                    return true
                }
                val current = boxes.getOrNull(selected)
                mode = if (current != null) cornerMode(current, x, y) else Mode.NONE
                if (mode == Mode.NONE) {
                    val hit = boxes.indexOfLast { it.contains(x, y) }
                    if (hit >= 0) {
                        selected = hit
                        mode = Mode.MOVE
                    } else {
                        boxes += RectF(x, y, x, y)
                        selected = boxes.lastIndex
                        mode = Mode.CREATE
                    }
                }
                startBox.set(boxes[selected])
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val box = boxes.getOrNull(selected) ?: return true
                val min = dp(32f)
                when (mode) {
                    Mode.MOVE -> {
                        box.set(startBox)
                        box.offset(
                            (x - downX).coerceIn(-startBox.left, width - startBox.right),
                            (y - downY).coerceIn(-startBox.top, height - startBox.bottom),
                        )
                    }
                    Mode.RESIZE_TL -> { box.left = min(x, box.right - min); box.top = min(y, box.bottom - min) }
                    Mode.RESIZE_TR -> { box.right = max(x, box.left + min); box.top = min(y, box.bottom - min) }
                    Mode.RESIZE_BL -> { box.left = min(x, box.right - min); box.bottom = max(y, box.top + min) }
                    Mode.RESIZE_BR -> { box.right = max(x, box.left + min); box.bottom = max(y, box.top + min) }
                    Mode.CREATE -> box.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                    Mode.NONE -> Unit
                }
                box.intersect(0f, 0f, width.toFloat(), height.toFloat())
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val box = boxes.getOrNull(selected)
                if (mode == Mode.CREATE && box != null && (box.width() < dp(32f) || box.height() < dp(24f))) {
                    // A tap on empty space: drop a subtitle-shaped box under the finger.
                    val w = width * 0.8f
                    val h = height * 0.12f
                    val left = (x - w / 2).coerceIn(0f, width - w)
                    val top = (y - h / 2).coerceIn(0f, height - h)
                    box.set(left, top, left + w, top + h)
                }
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun fractions(): List<TextArea> {
        if (width == 0 || height == 0) return emptyList()
        return boxes.map { TextArea(it.left / width, it.top / height, it.right / width, it.bottom / height).clamped() }
            .filter { it.width > 0.01f && it.height > 0.01f }
    }

    private fun cornerMode(box: RectF, x: Float, y: Float): Mode = when {
        near(x, y, box.left, box.top) -> Mode.RESIZE_TL
        near(x, y, box.right, box.top) -> Mode.RESIZE_TR
        near(x, y, box.left, box.bottom) -> Mode.RESIZE_BL
        near(x, y, box.right, box.bottom) -> Mode.RESIZE_BR
        else -> Mode.NONE
    }

    private fun corners(box: RectF) = listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)

    /** Just outside the box, left of the top-right corner, so it never fights the resize handle. */
    private fun deleteCenter(box: RectF): Pair<Float, Float> {
        val x = (box.right - dp(36f)).coerceAtLeast(box.left + dp(12f))
        val y = if (box.top > dp(30f)) box.top - dp(16f) else box.bottom + dp(16f)
        return x to y
    }

    private fun near(x: Float, y: Float, tx: Float, ty: Float, radiusDp: Float = 28f): Boolean =
        abs(x - tx) <= dp(radiusDp) && abs(y - ty) <= dp(radiusDp)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
