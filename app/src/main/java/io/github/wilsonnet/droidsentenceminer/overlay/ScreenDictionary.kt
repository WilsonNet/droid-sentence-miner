package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import io.github.wilsonnet.droidsentenceminer.capture.ScreenText
import io.github.wilsonnet.droidsentenceminer.capture.ScreenTextLine
import io.github.wilsonnet.droidsentenceminer.ui.components.AnchoredPopupPosition
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupCard
import io.github.wilsonnet.droidsentenceminer.ui.components.popupWidth
import io.github.wilsonnet.droidsentenceminer.ui.components.rememberPopupPlan
import io.github.wilsonnet.droidsentenceminer.ui.theme.DsmTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screen dictionary: over any app (a YouTube video, a game), the latest auto OCR read is laid as an
 * invisible, touchable text layer exactly over the real text. Tap a character to look up the word
 * starting there, hold and drag to select; the definition pops up next to it. The layer windows
 * (one per line) are fitted to the text, so touches anywhere else still reach the app.
 *
 * Videos move on, so a lookup pauses playback (media key) and resumes it when the popup closes;
 * while it is open the layer is frozen and auto OCR holds (it would read the popup otherwise).
 */
class ScreenDictionary(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val container: AppContainer,
) {
    private val layers = HashMap<Int, Pair<ScreenTextLayerView, WindowManager.LayoutParams>>()
    private var lines: List<ScreenTextLine> = emptyList()

    private var popup: ComposeView? = null
    private var popupOwner: OverlayLifecycleOwner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pausedMedia = false
    private val resume = Runnable { resumeMedia() }

    /** Pause the playing video during a lookup. */
    var pauseMedia = true

    /** A fresh read of the screen (auto OCR's [io.github.wilsonnet.droidsentenceminer.capture.AutoOcrController.readNow]). */
    var refresh: suspend () -> List<ScreenTextLine>? = { null }
    private var tapSeq = 0

    val popupOpen: Boolean get() = popup != null

    private val density = context.resources.displayMetrics.density

    fun update(newLines: List<ScreenTextLine>) {
        if (popupOpen || newLines == lines) return
        setLayer(newLines)
    }

    private fun setLayer(newLines: List<ScreenTextLine>) {
        lines = newLines
        val metrics = ScreenCaptureManager.screenMetrics(context)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val pad = 6 * density
        // One window per line, fitted to it: everything between lines (a game's buttons, the
        // empty part of a dialogue box) still reaches the app.
        (layers.keys - newLines.indices.toSet()).forEach { removeLayer(it) }
        for ((index, line) in newLines.withIndex()) {
            val bounds = line.bounds
            val left = max(0f, bounds.left * w - pad).roundToInt()
            val top = max(0f, bounds.top * h - pad).roundToInt()
            val right = min(w, bounds.right * w + pad).roundToInt()
            val bottom = min(h, bounds.bottom * h + pad).roundToInt()
            val glyphs = line.glyphs.map { g -> RectF(g.left * w - left, g.top * h - top, g.right * w - left, g.bottom * h - top) }
            val existing = layers[index]
            val view = existing?.first ?: ScreenTextLayerView(context)
            val params = existing?.second ?: layerParams()
            view.setLines(listOf(glyphs), listOf(index))
            view.onTap = { line, index -> lookupFrom(line, index) }
            view.onSelect = { line, range -> show(line, range) }
            params.x = left
            params.y = top
            params.width = right - left
            params.height = bottom - top
            if (existing == null) {
                layers[index] = view to params
                runCatching { windowManager.addView(view, params) }
            } else {
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    fun clear() {
        dismiss()
        layers.keys.toList().forEach { removeLayer(it) }
        lines = emptyList()
    }

    private fun removeLayer(line: Int) {
        layers.remove(line)?.let { (view, _) -> runCatching { windowManager.removeView(view) } }
    }

    /**
     * Tap: the word that covers the tapped character (see [TermLookup.wordAt]). The popup opens at
     * once from the layer, which is the last read (up to an auto OCR interval old); then the paused
     * screen is read again, and if the text under the finger changed in between (rolling captions,
     * a subtitle that switched as you tapped) the popup moves to the word really there.
     */
    private fun lookupFrom(line: Int, index: Int) {
        val target = lines.getOrNull(line) ?: return
        val glyph = target.glyphs.getOrNull(index) ?: return
        val seq = ++tapSeq
        scope.launch {
            val range = container.lookup.wordAt(target.text, index)
            show(line, range)
            delay(VERIFY_DELAY_MS) // let the pause take effect so the frame is the one being looked at
            // An empty read may just be a miss; only a read that sees other text overrides the tap.
            val fresh = refresh()?.takeIf { it.isNotEmpty() } ?: return@launch
            if (seq != tapSeq || !popupOpen) return@launch
            val hit = ScreenText.glyphAt(fresh, glyph.centerX, glyph.centerY)
            if (hit == null) {
                // The subtitle changed and there is no text under the finger any more.
                dismiss()
                setLayer(fresh)
                return@launch
            }
            val (freshLine, freshIndex) = hit
            val freshText = fresh[freshLine].text
            val freshRange = container.lookup.wordAt(freshText, freshIndex)
            if (freshText.substring(freshRange) == target.text.substring(range.first, min(range.last, target.text.lastIndex) + 1)) return@launch
            if (seq != tapSeq || !popupOpen) return@launch
            setLayer(fresh)
            show(freshLine, freshRange)
        }
    }

    private fun show(line: Int, range: IntRange) {
        val target = lines.getOrNull(line) ?: return
        val last = min(range.last, target.text.lastIndex)
        if (range.first > last) return
        dismiss(resumeLater = true)
        handler.removeCallbacks(resume)
        if (pauseMedia) pauseMediaPlayback()

        layers.values.forEach { (view, _) -> view.highlight(line, range.first..last) }
        val metrics = ScreenCaptureManager.screenMetrics(context)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val glyphs = (range.first..last).mapNotNull { target.glyphs.getOrNull(it) }.reduce { a, b -> a.union(b) }
        val anchor = Rect(glyphs.left * w, glyphs.top * h, glyphs.right * w, glyphs.bottom * h)
        val word = target.text.substring(range.first, last + 1)
        val allText = lines.joinToString("\n") { it.text }

        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
        }
        // An explicit width: a WRAP_CONTENT window is measured at the dialog width (320 dp) first.
        val popupWidthDp = popupWidth((w / density).dp, (h / density).dp)
        val params = WindowManager.LayoutParams(
            (popupWidthDp.value * density).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = anchor.bottom.roundToInt()
            cutoutAlways()
        }
        var side = io.github.wilsonnet.droidsentenceminer.ui.components.PopupPlacement.Side.BELOW
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            alpha = 0f // shown once measured and placed next to the word
            setContent {
                DsmTheme {
                    val local = androidx.compose.ui.platform.LocalDensity.current
                    val plan = rememberPopupPlan(anchor, w, h, popupWidthDp)
                    side = plan.side
                    WordLookupCard(
                        word = word,
                        sentence = allText,
                        image = null,
                        onDismiss = { dismiss() },
                        onMine = { details ->
                            // The log line has the sentence, its screenshot and its audio.
                            val logLine = container.textLog.lineForSharedText(allText)
                            container.miner.mineLine(logLine, word, details)
                        },
                        maxHeight = with(local) { plan.maxHeight.toDp() },
                        handleBack = false,
                        width = popupWidthDp,
                    )
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) dismiss(resumeLater = true)
                false
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val gap = (8 * density).roundToInt()
                val pos = AnchoredPopupPosition(anchor, gap, side).calculatePosition(
                    IntRect.Zero,
                    IntSize(w.toInt(), h.toInt()),
                    LayoutDirection.Ltr,
                    IntSize(v.width, v.height),
                )
                if (params.x != pos.x || params.y != pos.y) {
                    params.x = pos.x
                    params.y = pos.y
                    runCatching { windowManager.updateViewLayout(v, params) }
                }
                v.alpha = 1f
            }
        }
        popup = view
        popupOwner = owner
        runCatching { windowManager.addView(view, params) }
    }

    /**
     * Closes the popup. [resumeLater]: a tap outside may be on the next word, so playback resumes
     * only if no new lookup opens right away.
     */
    fun dismiss(resumeLater: Boolean = false) {
        val view = popup ?: return
        popup = null
        runCatching { windowManager.removeView(view) }
        popupOwner?.onDestroy()
        popupOwner = null
        layers.values.forEach { (layer, _) -> layer.highlight(-1, IntRange.EMPTY) }
        handler.removeCallbacks(resume)
        if (resumeLater) handler.postDelayed(resume, 350) else resumeMedia()
    }

    private companion object {
        const val VERIFY_DELAY_MS = 250L
        const val PAUSE_CHECK_MS = 500L
    }

    private fun pauseMediaPlayback() {
        if (pausedMedia) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (!audio.isMusicActive) return
        sendMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
        pausedMedia = true
        // Games play audio but ignore media keys, which go to the last media session instead
        // (a paused YouTube). Still audible: the pause missed, so don't send play later.
        handler.postDelayed({ if (audio.isMusicActive) pausedMedia = false }, PAUSE_CHECK_MS)
    }

    private fun resumeMedia() {
        if (!pausedMedia) return
        pausedMedia = false
        context.getSystemService(AudioManager::class.java)?.let { sendMediaKey(it, KeyEvent.KEYCODE_MEDIA_PLAY) }
    }

    private fun sendMediaKey(audio: AudioManager, code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun layerParams() = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        cutoutAlways()
    }

    private fun WindowManager.LayoutParams.cutoutAlways() {
        // Fractions are of the whole display; fullscreen video draws under the cutout too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}

/**
 * Invisible text over the real text: draws nothing but the looked-up word's highlight.
 * Tap = look up from that character; hold then drag = select characters on that line.
 */
@SuppressLint("ViewConstructor")
class ScreenTextLayerView(context: Context) : View(context) {
    private var glyphs: List<List<RectF>> = emptyList()
    private var lineIds: List<Int> = emptyList()
    private var marked: Pair<Int, IntRange>? = null
    private var selecting: Pair<Int, IntRange>? = null

    var onTap: (line: Int, index: Int) -> Unit = { _, _ -> }
    var onSelect: (line: Int, range: IntRange) -> Unit = { _, _ -> }

    private val markPaint = Paint().apply { color = 0x6633B5A5 }
    private val selectPaint = Paint().apply { color = 0x66E0A030 }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable { startSelection() }
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var anchor: Pair<Int, Int>? = null

    fun setLines(glyphs: List<List<RectF>>, lineIds: List<Int>) {
        this.glyphs = glyphs
        this.lineIds = lineIds
        invalidate()
    }

    /** [line] is the global line index; -1 clears. */
    fun highlight(line: Int, range: IntRange) {
        val local = lineIds.indexOf(line)
        marked = if (local >= 0 && !range.isEmpty()) local to range else null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        (selecting ?: marked)?.let { (line, range) ->
            val paint = if (selecting != null) selectPaint else markPaint
            for (i in range) glyphs.getOrNull(line)?.getOrNull(i)?.let { canvas.drawRect(it, paint) }
        }
    }

    private fun glyphAt(x: Float, y: Float, slack: Float = 8 * resources.displayMetrics.density): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestD = Float.MAX_VALUE
        glyphs.forEachIndexed { l, line ->
            line.forEachIndexed { i, g ->
                if (x < g.left - slack || x > g.right + slack || y < g.top - slack || y > g.bottom + slack) return@forEachIndexed
                val d = (g.centerX() - x) * (g.centerX() - x) + (g.centerY() - y) * (g.centerY() - y)
                if (d < bestD) {
                    bestD = d
                    best = l to i
                }
            }
        }
        return best
    }

    private fun startSelection() {
        val hit = glyphAt(downX, downY) ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        anchor = hit
        selecting = hit.first to hit.second..hit.second
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                anchor = null
                selecting = null
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val a = anchor
                if (a != null) {
                    glyphAt(event.x, event.y)?.takeIf { it.first == a.first }?.let { (_, i) ->
                        selecting = a.first to min(a.second, i)..max(a.second, i)
                        invalidate()
                    }
                } else if (!moved && (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val selection = selecting
                selecting = null
                if (selection != null) {
                    onSelect(lineIds[selection.first], selection.second)
                } else if (!moved) {
                    glyphAt(event.x, event.y)?.let { (l, i) -> onTap(lineIds[l], i) }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                selecting = null
                anchor = null
                invalidate()
            }
        }
        return true
    }
}
