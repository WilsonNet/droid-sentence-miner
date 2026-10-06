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
import io.github.wilsonnet.droidsentenceminer.audio.M4aEncoder
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import io.github.wilsonnet.droidsentenceminer.ocr.JapaneseTextScore
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
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

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleHidden = false

    private var captureOverlay: CaptureOverlayView? = null
    private var captureParams: WindowManager.LayoutParams? = null

    private var panelView: ComposeView? = null
    private var panelOwner: OverlayLifecycleOwner? = null
    private val panelState = OverlayPanelState()

    private var lastRegion = RectF(0.06f, 0.62f, 0.94f, 0.82f)
    private var lastCapture: Bitmap? = null
    private var pendingScan: RectF? = null
    private var pendingAudio: M4aEncoder.EncodedAudio? = null

    private var ocrEngine: MlKitJapaneseOcr? = null
    private var ocrEngineTwoPass: Boolean? = null
    private var ocrEngineDropNoise: Boolean? = null

    private fun ocrEngine(twoPass: Boolean, dropNoise: Boolean): MlKitJapaneseOcr {
        val current = ocrEngine
        if (current == null || ocrEngineTwoPass != twoPass || ocrEngineDropNoise != dropNoise) {
            ocrEngine = MlKitJapaneseOcr(twoPass = twoPass, dropNoise = dropNoise)
            ocrEngineTwoPass = twoPass
            ocrEngineDropNoise = dropNoise
        }
        return ocrEngine ?: MlKitJapaneseOcr(twoPass = twoPass, dropNoise = dropNoise)
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = AudioCaptureManager(this)
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
        // A rotation invalidates the virtual display; ask for consent again on the next scan.
        if (captureManager.isReady) {
            audioManager.stop()
            captureManager.release()
            startForegroundCompat(includeProjection = false)
        }
    }

    override fun onDestroy() {
        running.value = false
        container.appScope.launch { container.prefs.setOverlayRunning(false) }
        removeBubble()
        closeCaptureOverlay()
        hidePanel()
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
            val intent = Intent(this, ProjectionPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
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
    }

    private fun maybeStartAudioCapture() {
        scope.launch {
            val enabled = container.prefs.audioEnabled.first()
            if (!enabled || !AudioCaptureManager.isSupported()) return@launch
            if (!AudioCaptureManager.hasPermission(this@OverlayService)) return@launch
            val projection = captureManager.activeProjection ?: return@launch
            val started = audioManager.start(projection)
            if (!started) {
                panelState.status = "Audio capture unavailable: ${audioManager.error.orEmpty()}"
            }
        }
    }

    private fun runScan(region: RectF) {
        panelState.scanning = true
        panelState.status = null
        panelState.selectedWord = null
        panelState.selectedDetails = null
        panelState.definitionsHtml = null

        scope.launch {
            val frame = captureManager.grabFrame()
            if (frame == null) {
                panelState.scanning = false
                panelState.status = "Could not capture the screen. Try again."
                return@launch
            }
            val frac = ScreenCaptureManager.RectFrac(region.left, region.top, region.right, region.bottom)
            val cropped = captureManager.cropToRegion(frame, frac)
            lastCapture = cropped

            val result = try {
                val twoPass = container.prefs.ocrTwoPass.first()
                val dropNoise = container.prefs.ocrDropNoise.first()
                ocrEngine(twoPass, dropNoise).recognize(cropped)
            } catch (e: Exception) {
                panelState.scanning = false
                panelState.status = "OCR failed: ${e.message}"
                return@launch
            }

            val text = result.text.trim()
            if (text.isBlank() || JapaneseTextScore.score(text) < 1.0) {
                panelState.scanning = false
                panelState.status = "No Japanese text found (${result.elapsedMs} ms)"
                return@launch
            }

            val matches = container.lookup.scan(text)
            panelState.ocrText = text
            panelState.matches = matches
            panelState.scanning = false
            panelState.audioSegment = if (audioManager.isRunning) audioManager.latestSegment() else null
            val audioNote = panelState.audioSegment?.let { " · audio ${it.durationMs / 1000}s" }.orEmpty()
            panelState.status = "OCR ${result.elapsedMs} ms · ${matches.size} words found$audioNote"
            container.prefs.setLastScanText(text)
        }
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
            pendingAudio = null
            panelState.audioReady = false
            panelState.status = "Audio skipped"
        },
    )

    private fun applyAudio(segment: io.github.wilsonnet.droidsentenceminer.audio.AudioSegment) {
        panelState.audioEditorVisible = false
        panelState.status = "Encoding audio…"
        scope.launch {
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val file = java.io.File(cacheDir, "card-audio-${System.currentTimeMillis()}.m4a")
                M4aEncoder.encode(segment, file)
            }
            if (encoded != null) {
                pendingAudio = encoded
                panelState.audioReady = true
                panelState.status = "Audio ready (${"%.1f".format(segment.durationMs / 1000.0)}s)"
            } else {
                panelState.status = "Could not encode audio"
            }
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
        panelState.status = "Adding to Anki…"
        scope.launch {
            val audio = pendingAudio ?: audioSegment?.let { segment ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val file = java.io.File(cacheDir, "card-audio-auto.m4a")
                    M4aEncoder.encode(segment, file)
                }
            }
            if (audio != null) pendingAudio = audio
            val result = container.miner.mine(
                word = word,
                sentence = sentence,
                image = lastCapture,
                details = details,
                audio = audio,
            )
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
            .addAction(0, getString(R.string.ov_action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopSelfSafely() {
        hidePanel()
        closeCaptureOverlay()
        removeBubble()
        stopSelf()
    }

    companion object {
        const val ACTION_START = "io.github.wilsonnet.droidsentenceminer.action.START"
        const val ACTION_STOP = "io.github.wilsonnet.droidsentenceminer.action.STOP"
        const val ACTION_SCAN_REGION = "io.github.wilsonnet.droidsentenceminer.action.SCAN_REGION"
        const val ACTION_SCAN_FULL = "io.github.wilsonnet.droidsentenceminer.action.SCAN_FULL"
        const val ACTION_HIDE_BUBBLE = "io.github.wilsonnet.droidsentenceminer.action.HIDE_BUBBLE"
        const val ACTION_SHOW_MENU = "io.github.wilsonnet.droidsentenceminer.action.SHOW_MENU"
        const val ACTION_PROJECTION_RESULT = "io.github.wilsonnet.droidsentenceminer.action.PROJECTION_RESULT"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val NOTIFICATION_ID = 4211
        const val CHANNEL_ID = "dsm_overlay"

        private val running = MutableStateFlow(false)
        fun observeRunning(): StateFlow<Boolean> = running

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
