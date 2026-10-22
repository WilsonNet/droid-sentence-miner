package io.github.wilsonnet.droidsentenceminer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.MainActivity
import io.github.wilsonnet.droidsentenceminer.OverlayPanelState
import io.github.wilsonnet.droidsentenceminer.PanelMode
import io.github.wilsonnet.droidsentenceminer.R
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.audio.AudioCaptureManager
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.AppVisibility
import io.github.wilsonnet.droidsentenceminer.capture.AutoOcrController
import io.github.wilsonnet.droidsentenceminer.capture.TextArea
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import io.github.wilsonnet.droidsentenceminer.ocr.JapaneseTextScore
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The floating overlay experience: bubble -> region capture -> OCR -> dictionary
 * popup -> Anki. Runs as a foreground service and owns the MediaProjection session.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var container: AppContainer
    private lateinit var windowManager: WindowManager
    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var audioManager: AudioCaptureManager
    private lateinit var autoOcr: AutoOcrController
    private lateinit var screenDictionary: ScreenDictionary

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleHidden = false

    private var captureOverlay: CaptureOverlayView? = null
    private var captureParams: WindowManager.LayoutParams? = null

    private var panelView: ComposeView? = null

    private var liveView: ComposeView? = null
    private var liveOwner: OverlayLifecycleOwner? = null
    private var liveParams: WindowManager.LayoutParams? = null
    private var liveDismissed = false
    private val liveMode = androidx.compose.runtime.mutableStateOf(LiveMode.NORMAL)
    private var liveSizeDp = DEFAULT_LIVE_WIDTH_DP to DEFAULT_LIVE_HEIGHT_DP
    /** Where the window sat before going fullscreen. */
    private var liveRestorePosition: Pair<Int, Int>? = null

    private var areaSelector: AreaSelectorView? = null
    private var portraitAreas: List<TextArea> = emptyList()
    private var landscapeAreas: List<TextArea> = emptyList()
    private var panelOwner: OverlayLifecycleOwner? = null
    private val panelState = OverlayPanelState()

    private var lastRegion = RectF(0.06f, 0.62f, 0.94f, 0.82f)
    private var lastCapture: Bitmap? = null
    private var pendingScan: RectF? = null
    private var pendingAutoOcr = false

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(this)
        activeInstance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = AudioCaptureManager(this) { container.textLog.audioBuffer }
        captureManager = ScreenCaptureManager(this).apply {
            onProjectionStopped = {
                audioManager.stop()
                scope.launch {
                    if (panelState.scanning) {
                        panelState.scanning = false
                        panelState.status = "Screen capture was stopped"
                    }
                }
            }
        }

        screenDictionary = ScreenDictionary(this, windowManager, scope, container)
        autoOcr = AutoOcrController(
            scope,
            captureManager,
            container.textLog,
            container.prefs,
            container,
            areas = { currentAreas() },
            paused = {
                AppVisibility.mainResumed || panelView != null || areaSelector != null || !captureManager.contentVisible ||
                    screenDictionary.popupOpen || (liveView != null && liveMode.value == LiveMode.FULLSCREEN)
            },
            holdLayer = { screenDictionary.popupOpen },
        )
        scope.launch {
            kotlinx.coroutines.flow.combine(autoOcr.screenText, container.prefs.screenDictionary) { lines, on -> if (on) lines else emptyList() }
                .collect { lines -> if (lines.isEmpty() && !screenDictionary.popupOpen) screenDictionary.clear() else screenDictionary.update(lines) }
        }
        scope.launch { container.prefs.pauseOnLookup.collect { screenDictionary.pauseMedia = it } }
        scope.launch { container.prefs.autoAreas(landscape = false).collect { portraitAreas = it } }
        scope.launch { container.prefs.autoAreas(landscape = true).collect { landscapeAreas = it } }
        scope.launch {
            autoOcr.status.collect {
                val changed = panelState.autoOcrRunning != it.running
                panelState.autoOcrRunning = it.running
                autoOcrState.value = it
                if (changed && running.value) startForegroundCompat(includeProjection = captureManager.isReady)
                if (changed) {
                    if (it.running) {
                        liveDismissed = false
                        if (container.prefs.liveTextWindow.first()) showLiveWindow()
                    } else {
                        hideLiveWindow()
                    }
                }
            }
        }

        scope.launch {
            val region = container.prefs.captureRegion.first()
            lastRegion = RectF(region.left, region.top, region.right, region.bottom)
            panelState.ocrText = container.prefs.lastScanText.first()
            installBubble()
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                running.value = true
                scope.launch { container.prefs.setOverlayRunning(true) }
                if (bubbleView == null) installBubble()
            }
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_SCAN_REGION -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                showCaptureOverlay()
            }
            ACTION_SCAN_FULL -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                performScan(RectF(0f, 0f, 1f, 1f))
            }
            ACTION_HIDE_BUBBLE -> {
                removeBubble()
                hidePanel()
            }
            ACTION_SHOW_MENU -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                showMenu()
            }
            ACTION_AUTO_OCR_START -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                running.value = true
                if (bubbleView == null) installBubble()
                startAutoOcr()
            }
            ACTION_AUTO_OCR_STOP -> autoOcr.stop()
            ACTION_SELECT_AREAS -> {
                startForegroundCompat(includeProjection = captureManager.isReady)
                running.value = true
                if (bubbleView == null) installBubble()
                showAreaSelector()
            }
            ACTION_PROJECTION_RESULT -> handleProjectionResult(intent)
            null -> {
                // Service restarted by the system; make sure we are a valid foreground service.
                startForegroundCompat(includeProjection = captureManager.isReady)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenDictionary.clear()
        liveParams?.let { p ->
            scope.launch {
                restoreLiveGeometry(p)
                applyLiveMode(p)
                liveView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
            }
        }
        // Games usually run in landscape: keep capturing across rotations by resizing the display.
        if (captureManager.isReady && !captureManager.onScreenSizeChanged()) {
            autoOcr.stop()
            audioManager.stop()
            captureManager.release()
            startForegroundCompat(includeProjection = false)
        }
    }

    override fun onDestroy() {
        running.value = false
        container.appScope.launch { container.prefs.setOverlayRunning(false) }
        if (activeInstance === this) activeInstance = null
        removeBubble()
        closeCaptureOverlay()
        closeAreaSelector()
        hidePanel()
        hideLiveWindow()
        screenDictionary.clear()
        autoOcr.stop()
        audioManager.stop()
        captureManager.release()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------ bubble

    private fun installBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val size = (56 * density).roundToInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (captureManager.screenWidth - size - (12 * density).toInt()).coerceAtLeast(0)
            y = (captureManager.screenHeight * 0.42f).roundToInt()
        }

        scope.launch {
            container.prefs.bubblePosition.first()?.let { (x, y) ->
                params.x = x
                params.y = y
            }
            val view = BubbleView(
                this@OverlayService,
                onTap = { showCaptureOverlay() },
                onMove = { dx, dy -> moveBubble(dx, dy) },
                onLongPress = { showMenu() },
                onRelease = { snapBubbleAndSave() },
            )
            bubbleView = view
            bubbleParams = params
            runCatching { windowManager.addView(view, params) }
            bubbleHidden = false
        }
    }

    private fun moveBubble(dx: Float, dy: Float) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val density = resources.displayMetrics.density
        val size = (56 * density).roundToInt()
        params.x = (params.x + dx).roundToInt().coerceIn(0, max(0, captureManager.screenWidth - size))
        params.y = (params.y + dy).roundToInt().coerceIn(0, max(0, captureManager.screenHeight - size))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun snapBubbleAndSave() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val density = resources.displayMetrics.density
        val size = (56 * density).roundToInt()
        val edge = (12 * density).roundToInt()
        params.x = if (params.x + size / 2 < captureManager.screenWidth / 2) edge else captureManager.screenWidth - size - edge
        runCatching { windowManager.updateViewLayout(view, params) }
        scope.launch { container.prefs.setBubblePosition(params.x, params.y) }
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    // ----------------------------------------------------- capture region mode

    private fun showCaptureOverlay() {
        if (captureOverlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        val screenW = captureManager.screenWidth
        val screenH = captureManager.screenHeight
        val initial = RectF(
            lastRegion.left * screenW,
            lastRegion.top * screenH,
            lastRegion.right * screenW,
            lastRegion.bottom * screenH,
        )
        val view = CaptureOverlayView(
            this,
            initial,
            onScan = { fraction -> applyRegion(fraction) },
            onCancel = { closeCaptureOverlay() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        captureOverlay = view
        captureParams = params
        runCatching { windowManager.addView(view, params) }
    }

    private fun closeCaptureOverlay() {
        captureOverlay?.let { runCatching { windowManager.removeView(it) } }
        captureOverlay = null
        captureParams = null
    }

    private fun applyRegion(fraction: RectF) {
        closeCaptureOverlay()
        lastRegion = RectF(fraction)
        scope.launch {
            container.prefs.setCaptureRegion(
                io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.CaptureRegion(
                    left = fraction.left,
                    top = fraction.top,
                    right = fraction.right,
                    bottom = fraction.bottom,
                ),
            )
        }
        performScan(fraction)
    }

    // ------------------------------------------------------------------- scan

    private fun performScan(region: RectF) {
        showPanel()
        panelState.mode = PanelMode.RESULT
        panelState.status = null
        if (!captureManager.isReady) {
            pendingScan = region
            requestProjection()
            panelState.scanning = false
            panelState.status = "Waiting for screen capture permission…"
            return
        }
        runScan(region)
    }

    private fun handleProjectionResult(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            pendingScan = null
            pendingAutoOcr = false
            panelState.scanning = false
            panelState.status = "Screen capture permission denied"
            return
        }
        try {
            captureManager.start(resultCode, data) { startForegroundCompat(includeProjection = true) }
            maybeStartAudioCapture()
        } catch (e: Exception) {
            panelState.scanning = false
            panelState.status = "Capture start failed: ${e.message}"
            return
        }
        pendingScan?.let { pending ->
            pendingScan = null
            runScan(pending)
        }
        if (pendingAutoOcr) {
            pendingAutoOcr = false
            autoOcr.start()
        }
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.widget.Toast.makeText(
                this,
                "Tip: choose \"A single app\" and pick your game to capture only it",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        val intent = Intent(this, ProjectionPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startAutoOcr() {
        if (autoOcr.isRunning) return
        if (!captureManager.isReady) {
            pendingAutoOcr = true
            requestProjection()
            return
        }
        // Audio may have been enabled since the projection started.
        maybeStartAudioCapture()
        autoOcr.start()
        // Started from inside the game with nothing selected yet: go straight to picking areas.
        if (currentAreas().isEmpty() && !AppVisibility.mainResumed) showAreaSelector()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun currentAreas(): List<TextArea> = if (isLandscape()) landscapeAreas else portraitAreas

    // ------------------------------------------------------------ text areas

    private fun showAreaSelector() {
        if (areaSelector != null || !Settings.canDrawOverlays(this)) return
        hidePanel()
        closeCaptureOverlay()
        val landscape = isLandscape()
        val view = AreaSelectorView(
            this,
            initial = currentAreas(),
            orientationLabel = if (landscape) "landscape" else "portrait",
            onSave = { areas ->
                closeAreaSelector()
                scope.launch {
                    container.prefs.setAutoAreas(landscape, areas)
                    if (areas.isNotEmpty() && !autoOcr.isRunning) startAutoOcr()
                }
            },
            onCancel = { closeAreaSelector() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        liveView?.visibility = android.view.View.GONE
        areaSelector = view
        runCatching { windowManager.addView(view, params) }
    }

    private fun closeAreaSelector() {
        areaSelector?.let { runCatching { windowManager.removeView(it) } }
        areaSelector = null
        liveView?.visibility = android.view.View.VISIBLE
    }

    // ------------------------------------------------------------ live text

    private fun showLiveWindow() {
        if (liveView != null || liveDismissed || !Settings.canDrawOverlays(this)) return
        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
        }
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).roundToInt()
            y = (40 * density).roundToInt()
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                LiveTextWindow(
                    dao = container.textLog.dao,
                    status = autoOcr.status,
                    mode = liveMode.value,
                    onMode = { setLiveMode(it) },
                    onLineClick = { id -> showLatestLine(id) },
                    onDrag = { dx, dy -> moveLiveWindow(dx, dy) },
                    onDragEnd = { saveLiveWindowPosition() },
                    onResize = { dx, dy -> resizeLiveWindow(dx, dy) },
                    onResizeEnd = {
                        val (w, h) = liveSizeDp
                        val landscape = liveLandscape()
                        scope.launch { container.prefs.setLiveWindowSizeDp(landscape, w, h) }
                    },
                    onEditAreas = { showAreaSelector() },
                    onClose = {
                        liveDismissed = true
                        hideLiveWindow()
                    },
                )
            }
        }
        liveOwner = owner
        liveView = view
        liveParams = params
        scope.launch {
            restoreLiveGeometry(params)
            liveMode.value = container.prefs.liveWindowMode.first()
                ?.let { name -> LiveMode.entries.firstOrNull { it.name == name } }
                // Fullscreen is for a quick look back; reopening it there would hide the game.
                ?.takeIf { it != LiveMode.FULLSCREEN }
                ?: LiveMode.NORMAL
            applyLiveMode(params)
            runCatching { windowManager.addView(view, params) }
        }
    }

    /** From the real screen size: the service's Configuration can lag a rotation. */
    private fun liveLandscape(): Boolean {
        val metrics = ScreenCaptureManager.screenMetrics(this)
        return metrics.widthPixels > metrics.heightPixels
    }

    /** Loads this orientation's saved position and size (defaults when there is none yet). */
    private suspend fun restoreLiveGeometry(params: WindowManager.LayoutParams) {
        val landscape = liveLandscape()
        val density = resources.displayMetrics.density
        val position = container.prefs.liveWindowPosition(landscape).first()
            ?: ((12 * density).roundToInt() to (40 * density).roundToInt())
        liveSizeDp = container.prefs.liveWindowSizeDp(landscape).first() ?: (DEFAULT_LIVE_WIDTH_DP to DEFAULT_LIVE_HEIGHT_DP)
        if (liveMode.value == LiveMode.FULLSCREEN) {
            liveRestorePosition = position
        } else {
            params.x = position.first
            params.y = position.second
        }
    }

    private fun setLiveMode(mode: LiveMode) {
        val params = liveParams ?: return
        val old = liveMode.value
        if (mode == old) return
        if (mode == LiveMode.FULLSCREEN) {
            liveRestorePosition = params.x to params.y
        } else if (old == LiveMode.FULLSCREEN) {
            liveRestorePosition?.let { (x, y) ->
                params.x = x
                params.y = y
            }
        }
        liveMode.value = mode
        applyLiveMode(params)
        liveView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        scope.launch { container.prefs.setLiveWindowMode(mode.name) }
    }

    /**
     * Sizes the window for its mode. Explicit pixel sizes, not WRAP_CONTENT: an overlay window is
     * first measured at the 320 dp dialog width, and a scrolling list has no content height.
     */
    private fun applyLiveMode(params: WindowManager.LayoutParams) {
        val metrics = ScreenCaptureManager.screenMetrics(this)
        val density = resources.displayMetrics.density
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        val width = (liveSizeDp.first * density).roundToInt().coerceIn((MIN_LIVE_WIDTH_DP * density).roundToInt(), sw)
        when (liveMode.value) {
            LiveMode.FULLSCREEN -> {
                params.x = 0
                params.y = 0
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
            }
            LiveMode.COMPACT -> {
                params.width = width
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            LiveMode.NORMAL -> {
                params.width = width
                params.height = (liveSizeDp.second * density).roundToInt().coerceIn((MIN_LIVE_HEIGHT_DP * density).roundToInt(), sh)
            }
        }
        if (liveMode.value != LiveMode.FULLSCREEN) {
            params.x = params.x.coerceIn(0, max(0, sw - params.width))
            val h = if (params.height > 0) params.height else (48 * density).roundToInt()
            params.y = params.y.coerceIn(0, max(0, sh - h))
        }
    }

    private fun moveLiveWindow(dx: Float, dy: Float) {
        val view = liveView ?: return
        val params = liveParams ?: return
        val metrics = ScreenCaptureManager.screenMetrics(this)
        params.x = (params.x + dx).roundToInt().coerceIn(0, max(0, metrics.widthPixels - view.width))
        params.y = (params.y + dy).roundToInt().coerceIn(0, max(0, metrics.heightPixels - view.height))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun saveLiveWindowPosition() {
        val landscape = liveLandscape()
        liveParams?.let { p -> scope.launch { container.prefs.setLiveWindowPosition(landscape, p.x, p.y) } }
    }

    private fun resizeLiveWindow(dx: Float, dy: Float) {
        val view = liveView ?: return
        val params = liveParams ?: return
        if (liveMode.value != LiveMode.NORMAL) return
        val metrics = ScreenCaptureManager.screenMetrics(this)
        val density = resources.displayMetrics.density
        params.width = (params.width + dx).roundToInt()
            .coerceIn((MIN_LIVE_WIDTH_DP * density).roundToInt(), max((MIN_LIVE_WIDTH_DP * density).roundToInt(), metrics.widthPixels - params.x))
        params.height = (params.height + dy).roundToInt()
            .coerceIn((MIN_LIVE_HEIGHT_DP * density).roundToInt(), max((MIN_LIVE_HEIGHT_DP * density).roundToInt(), metrics.heightPixels - params.y))
        liveSizeDp = (params.width / density).roundToInt() to (params.height / density).roundToInt()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun hideLiveWindow() {
        liveView?.let { runCatching { windowManager.removeView(it) } }
        liveView = null
        liveParams = null
        liveOwner?.onDestroy()
        liveOwner = null
    }

    private fun toggleAutoOcr() {
        if (autoOcr.isRunning) {
            autoOcr.stop()
            panelState.status = "Auto OCR stopped"
        } else {
            hidePanel()
            startAutoOcr()
        }
    }

    private fun maybeStartAudioCapture() {
        scope.launch {
            val enabled = container.prefs.audioEnabled.first()
            if (!enabled || !AudioCaptureManager.isSupported()) return@launch
            if (!AudioCaptureManager.hasPermission(this@OverlayService)) return@launch
            if (audioManager.isRunning) return@launch
            val projection = captureManager.activeProjection ?: return@launch
            container.textLog.ensureAudioBuffer(container.prefs.audioBufferSeconds.first())
            val started = audioManager.start(projection)
            if (!started) {
                panelState.status = "Audio capture unavailable: ${audioManager.error.orEmpty()}"
            }
        }
    }

    private fun runScan(region: RectF) {
        scope.launch { executeScan(region) }
    }

    internal data class ScanOutcome(
        val text: String,
        val elapsedMs: Long,
        val matchCount: Int,
        val status: String,
    )

    /** Full capture -> OCR -> lookup pipeline; updates [panelState] along the way. */
    internal suspend fun executeScan(region: RectF): ScanOutcome {
        panelState.scanning = true
        panelState.status = null
        panelState.selectedWord = null
        panelState.selectedDetails = null
        panelState.definitionsHtml = null
        panelState.lineId = null
        panelState.audioSegment = null
        panelState.audioReady = false
        panelState.audioSkipped = false

        val frame = captureManager.grabFrame()
        if (frame == null) {
            panelState.scanning = false
            val status = "Could not capture the screen. Try again."
            panelState.status = status
            return ScanOutcome("", 0L, 0, status)
        }
        val frac = ScreenCaptureManager.RectFrac(region.left, region.top, region.right, region.bottom)
        val cropped = captureManager.cropToRegion(frame, frac)
        lastCapture = cropped

        val result = try {
            val twoPass = container.prefs.ocrTwoPass.first()
            val dropNoise = container.prefs.ocrDropNoise.first()
            container.ocrEngine(twoPass, dropNoise).recognize(cropped)
        } catch (e: Exception) {
            panelState.scanning = false
            val status = "OCR failed: ${e.message}"
            panelState.status = status
            return ScanOutcome("", 0L, 0, status)
        }

        val text = result.text.trim()
        if (text.isBlank() || JapaneseTextScore.score(text) < 1.0) {
            panelState.scanning = false
            val status = "No Japanese text found (${result.elapsedMs} ms)"
            panelState.status = status
            return ScanOutcome(text, result.elapsedMs, 0, status)
        }

        val matches = container.lookup.scan(text)
        panelState.ocrText = text
        panelState.matches = matches
        panelState.scanning = false
        // Manual scans join the text log too, so they can be mined later with their audio.
        val now = System.currentTimeMillis()
        val lineId = container.textLog.startLine(text, now, TextLineEntity.SOURCE_MANUAL, frame)
        container.textLog.endLine(lineId, now)
        panelState.lineId = lineId
        panelState.audioSegment = if (audioManager.isRunning) {
            container.textLog.get(lineId)?.let { container.textLog.clip(it)?.segment }
        } else {
            null
        }
        val audioNote = panelState.audioSegment?.let { " · audio ${it.durationMs / 1000}s" }.orEmpty()
        val status = "OCR ${result.elapsedMs} ms · ${matches.size} words found$audioNote"
        panelState.status = status
        container.prefs.setLastScanText(text)
        return ScanOutcome(text, result.elapsedMs, matches.size, status)
    }

    private fun retryWithSameRegion() {
        performScan(RectF(lastRegion))
    }

    // ------------------------------------------------------------------ panel

    private fun showMenu() {
        showPanel()
        panelState.mode = PanelMode.MENU
        panelState.status = null
    }

    private fun showPanel() {
        if (panelView == null) {
            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onStart()
            }
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    OverlayPanel(
                        state = panelState,
                        dark = isDarkMode(),
                        callbacks = overlayCallbacks(),
                    )
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            panelOwner = owner
            panelView = view
            runCatching { windowManager.addView(view, params) }
        }
    }

    private fun hidePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelOwner?.onDestroy()
        panelOwner = null
    }

    private fun overlayCallbacks(): OverlayCallbacks = OverlayCallbacks(
        onClose = { hidePanel() },
        onScanRegion = { retryWithSameRegion() },
        onScanFullScreen = { performScan(RectF(0f, 0f, 1f, 1f)) },
        onStopOverlay = { stopSelfSafely() },
        onHideBubble = {
            removeBubble()
            hidePanel()
        },
        onWordClick = { word -> lookupWord(word) },
        onMine = { mineSelected() },
        onCopyText = { copyOcrText() },
        onEditAudio = { panelState.audioEditorVisible = true },
        onAudioApplied = { segment -> applyAudio(segment) },
        onAudioSkipped = {
            panelState.audioEditorVisible = false
            panelState.audioSegment = null
            panelState.audioReady = false
            panelState.audioSkipped = true
            panelState.status = "Audio skipped"
        },
        onToggleAutoOcr = { toggleAutoOcr() },
        onLatestLine = { showLatestLine() },
        onSelectAreas = { showAreaSelector() },
        onShowLiveText = {
            hidePanel()
            liveDismissed = false
            showLiveWindow()
        },
    )

    private fun applyAudio(segment: AudioSegment) {
        panelState.audioEditorVisible = false
        panelState.audioSegment = segment
        panelState.audioReady = true
        panelState.audioSkipped = false
        panelState.status = "Audio ready (${"%.1f".format(segment.durationMs / 1000.0)}s)"
    }

    /** Loads a logged line (the newest by default) into the panel to mine it without leaving the game. */
    private fun showLatestLine(lineId: Long? = null) {
        showPanel()
        panelState.mode = PanelMode.RESULT
        panelState.selectedWord = null
        panelState.selectedDetails = null
        panelState.definitionsHtml = null
        panelState.audioReady = false
        panelState.audioSkipped = false
        scope.launch {
            val line = lineId?.let { container.textLog.get(it) } ?: container.textLog.latest()
            if (line == null) {
                panelState.ocrText = ""
                panelState.matches = emptyList()
                panelState.lineId = null
                panelState.status = "No lines yet — start auto OCR or scan a region"
                return@launch
            }
            panelState.ocrText = line.text
            panelState.matches = container.lookup.scan(line.text)
            panelState.lineId = line.id
            lastCapture = container.textLog.screenshot(line)
            val clip = container.textLog.clip(line)
            panelState.audioSegment = clip?.segment
            val audioNote = clip?.let { " · audio ${"%.1f".format(it.segment.durationMs / 1000.0)}s" } ?: " · no audio in buffer"
            panelState.status = "Latest line$audioNote"
        }
    }

    private fun lookupWord(word: String) {
        panelState.selectedWord = word
        panelState.selectedDetails = null
        panelState.definitionsHtml = null
        panelState.loadingDetails = true
        scope.launch {
            val details = container.lookup.lookup(word)
            val html = container.miner.buildPopupHtml(word, details, isDarkMode())
            panelState.selectedDetails = details
            panelState.definitionsHtml = html
            panelState.loadingDetails = false
        }
    }

    private fun mineSelected() {
        val word = panelState.selectedWord ?: return
        val sentence = panelState.ocrText
        val details = panelState.selectedDetails
        val audioSegment = panelState.audioSegment
        val skipped = panelState.audioSkipped
        val lineId = panelState.lineId
        panelState.status = "Adding to Anki…"
        scope.launch {
            val line = lineId?.let { container.textLog.get(it) }
            val result = if (line != null) {
                container.miner.mineLine(line, word, details, audio = audioSegment, includeAudio = !skipped)
            } else {
                container.miner.mine(word = word, sentence = sentence, image = lastCapture, details = details)
            }
            panelState.status = when (result) {
                is AnkiResult.Success -> "Added to Anki"
                is AnkiResult.Error -> "Anki: ${result.message}"
                AnkiResult.NotAvailable -> {
                    val pkg = com.ichi2.anki.api.AddContentApi.getAnkiDroidPackageName(this@OverlayService)
                    if (pkg == null) "AnkiDroid is not installed and AnkiConnect is not configured" else "AnkiDroid permission missing — grant it in the app"
                }
            }
        }
    }

    private fun copyOcrText() {
        if (panelState.ocrText.isBlank()) {
            panelState.status = "Nothing to copy"
            return
        }
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Droid Sentence Miner", panelState.ocrText))
            panelState.status = "Copied to clipboard"
        } catch (e: Exception) {
            panelState.status = "Clipboard unavailable: ${e.message}"
        }
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ----------------------------------------------------------- notification

    private fun startForegroundCompat(includeProjection: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (includeProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                if (includeProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.overlay_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scanRegion = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_SCAN_REGION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val autoToggle = PendingIntent.getService(
            this,
            3,
            Intent(this, OverlayService::class.java)
                .setAction(if (autoOcr.isRunning) ACTION_AUTO_OCR_STOP else ACTION_AUTO_OCR_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(getString(R.string.ov_service_title))
            .setContentText(getString(R.string.ov_service_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.ov_action_scan), scanRegion)
            .addAction(0, if (autoOcr.isRunning) "Stop auto OCR" else "Auto OCR", autoToggle)
            .addAction(0, getString(R.string.ov_action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopSelfSafely() {
        hidePanel()
        hideLiveWindow()
        closeAreaSelector()
        closeCaptureOverlay()
        removeBubble()
        stopSelf()
    }

    // --------------------------------------------------- test/debug accessors

    internal val debugPanelState: OverlayPanelState get() = panelState
    internal val debugContainer: AppContainer get() = container
    internal val debugCaptureManager: ScreenCaptureManager get() = captureManager
    internal val debugAudioRunning: Boolean get() = audioManager.isRunning

    /** The screen dictionary's current text layer and whether its popup is open (debug API). */
    internal val debugScreenText get() = autoOcr.screenText.value
    internal val debugScreenPopupOpen get() = screenDictionary.popupOpen

    /** Live text window: shown, mode, and window rect in px [x, y, w, h] (debug API). */
    internal val debugLiveWindow: Triple<Boolean, LiveMode, List<Int>>
        get() = Triple(liveView != null, liveMode.value, listOf(liveParams?.x ?: 0, liveParams?.y ?: 0, liveView?.width ?: 0, liveView?.height ?: 0))

    /** Shows the live text window (`show`) and/or switches its mode, on the main thread. */
    internal fun debugSetLive(show: Boolean, mode: LiveMode?) {
        android.os.Handler(mainLooper).post {
            if (show) {
                liveDismissed = false
                showLiveWindow()
            }
            if (mode != null) {
                if (liveView != null) setLiveMode(mode) else liveMode.value = mode
            }
        }
    }

    internal fun debugSetRegion(region: RectF) {
        lastRegion = RectF(region)
    }

    internal val debugLandscape: Boolean get() = isLandscape()

    /** Kicks off a scan (requesting projection consent when needed). */
    internal fun debugRequestScan(region: RectF) {
        android.os.Handler(mainLooper).post { performScan(region) }
    }

    companion object {
        private const val DEFAULT_LIVE_WIDTH_DP = 340
        private const val DEFAULT_LIVE_HEIGHT_DP = 200
        private const val MIN_LIVE_WIDTH_DP = 200
        private const val MIN_LIVE_HEIGHT_DP = 96

        @Volatile
        internal var activeInstance: OverlayService? = null
            private set

        const val ACTION_START = "io.github.wilsonnet.droidsentenceminer.action.START"
        const val ACTION_STOP = "io.github.wilsonnet.droidsentenceminer.action.STOP"
        const val ACTION_SCAN_REGION = "io.github.wilsonnet.droidsentenceminer.action.SCAN_REGION"
        const val ACTION_SCAN_FULL = "io.github.wilsonnet.droidsentenceminer.action.SCAN_FULL"
        const val ACTION_HIDE_BUBBLE = "io.github.wilsonnet.droidsentenceminer.action.HIDE_BUBBLE"
        const val ACTION_SHOW_MENU = "io.github.wilsonnet.droidsentenceminer.action.SHOW_MENU"
        const val ACTION_AUTO_OCR_START = "io.github.wilsonnet.droidsentenceminer.action.AUTO_OCR_START"
        const val ACTION_SELECT_AREAS = "io.github.wilsonnet.droidsentenceminer.action.SELECT_AREAS"
        const val ACTION_AUTO_OCR_STOP = "io.github.wilsonnet.droidsentenceminer.action.AUTO_OCR_STOP"
        const val ACTION_PROJECTION_RESULT = "io.github.wilsonnet.droidsentenceminer.action.PROJECTION_RESULT"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val NOTIFICATION_ID = 4211
        const val CHANNEL_ID = "dsm_overlay"

        private val running = MutableStateFlow(false)
        fun observeRunning(): StateFlow<Boolean> = running

        private val autoOcrState = MutableStateFlow(AutoOcrController.Status())
        fun observeAutoOcr(): StateFlow<AutoOcrController.Status> = autoOcrState

        fun startAutoOcr(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_AUTO_OCR_START),
            )
        }

        fun selectAreas(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_SELECT_AREAS),
            )
        }

        fun stopAutoOcr(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_AUTO_OCR_STOP),
            )
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
            )
        }

        fun scanRegion(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_SCAN_REGION),
            )
        }
    }
}
