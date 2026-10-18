package io.github.wilsonnet.droidsentenceminer.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Keeps a single MediaProjection session alive and hands out bitmap frames on demand.
 *
 * Notes:
 *  - On Android 14+ the projection must be created by a foreground service that declares
 *    the mediaProjection type, and a callback must be registered before creating the
 *    virtual display. The OverlayService takes care of both.
 *  - Only one virtual display is created per consent token; frames are pulled on demand.
 */
class ScreenCaptureManager(private val context: Context) {

    @Volatile
    private var projection: MediaProjection? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var heldImage: Image? = null

    @Volatile
    private var lastFrameBitmap: Bitmap? = null

    @Volatile
    private var lastFrameAt: Long = 0L

    var isReady: Boolean = false
        private set

    /**
     * False while the app shared via "A single app" is not on screen (Android 14+); its frames
     * are blank then, so auto OCR pauses instead of reading other apps.
     */
    @Volatile
    var contentVisible: Boolean = true
        private set

    /** The active projection, shared with the audio playback capture. */
    val activeProjection: MediaProjection? get() = projection

    var onProjectionStopped: (() -> Unit)? = null

    var screenWidth: Int
        private set
    var screenHeight: Int
        private set
    var densityDpi: Int
        private set

    init {
        val metrics = screenMetrics(context)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    /**
     * Must be called while the owning foreground service is running.
     *
     * [promoteForeground] switches the service to the mediaProjection foreground type.
     * Android 15+ only allows that once the consent token exists (the `project_media`
     * app-op is set when the user grants consent), so on those devices the projection
     * is created first and the service type is promoted afterwards.
     */
    @SuppressLint("WrongConstant")
    fun start(resultCode: Int, data: Intent, promoteForeground: () -> Unit) {
        release()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        var created: MediaProjection? = null
        try {
            promoteForeground()
        } catch (e: SecurityException) {
            created = manager.getMediaProjection(resultCode, data)
            promoteForeground()
        }
        val newProjection = created ?: manager.getMediaProjection(resultCode, data)
        ?: throw IllegalStateException("MediaProjection consent was not granted")

        val thread = HandlerThread("dsm-capture").also { it.start() }
        val h = Handler(thread.looper)

        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    isReady = false
                    release()
                    onProjectionStopped?.invoke()
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    contentVisible = isVisible
                }
            },
            h,
        )

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        val display = newProjection.createVirtualDisplay(
            "dsm-capture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            h,
        )
        reader.setOnImageAvailableListener(::onImageAvailable, h)

        projection = newProjection
        virtualDisplay = display
        imageReader = reader
        handlerThread = thread
        handler = h
        isReady = true
    }

    /**
     * Follows a rotation without asking for consent again: the virtual display is resized and
     * pointed at a reader of the new size (a projection token can't create a second display).
     * Returns false when there is nothing to resize.
     */
    @SuppressLint("WrongConstant")
    fun onScreenSizeChanged(): Boolean {
        val metrics = screenMetrics(context)
        if (metrics.widthPixels == screenWidth && metrics.heightPixels == screenHeight) return true
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
        val display = virtualDisplay ?: return false
        val h = handler ?: return false
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener(::onImageAvailable, h)
        val old = imageReader
        display.resize(screenWidth, screenHeight, densityDpi)
        display.surface = reader.surface
        imageReader = reader
        synchronized(this) {
            runCatching { heldImage?.close() }
            heldImage = null
            lastFrameBitmap = null
        }
        runCatching { old?.close() }
        return true
    }

    private fun onImageAvailable(reader: ImageReader) {
        synchronized(this) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
            heldImage?.close()
            heldImage = image
            lastFrameAt = SystemClock.elapsedRealtime()
        }
    }

    fun release() {
        isReady = false
        contentVisible = true
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        synchronized(this) {
            runCatching { heldImage?.close() }
            heldImage = null
            lastFrameBitmap = null
        }
        runCatching { projection?.stop() }
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    /** Grabs the most recent frame, waiting briefly for a fresh one if needed. */
    suspend fun grabFrame(timeoutMs: Long = 2000L, maxAgeMs: Long = 350L): Bitmap? {
        if (!isReady) return null
        // With a cached frame available, don't stall scans on static screens.
        val effectiveTimeout = if (lastFrameBitmap != null) minOf(timeoutMs, 800L) else timeoutMs
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < effectiveTimeout) {
            val fresh = lastFrameAt > 0 && SystemClock.elapsedRealtime() - lastFrameAt <= maxAgeMs
            if (fresh) {
                val bitmap = synchronized(this) {
                    heldImage?.let { img ->
                        val bmp = img.toBitmapSafe()
                        img.close()
                        heldImage = null
                        bmp
                    }
                }
                if (bitmap != null) {
                    lastFrameBitmap = bitmap
                    return bitmap
                }
            }
            delay(32)
        }
        // A static screen produces no new buffers, so fall back to the last frame we saw.
        synchronized(this) {
            heldImage?.let { img ->
                val bmp = img.toBitmapSafe()
                img.close()
                heldImage = null
                lastFrameBitmap = bmp
            }
        }
        return lastFrameBitmap
    }

    data class Frame(val bitmap: Bitmap, val atMs: Long)

    /**
     * Non-blocking: the newest frame if the screen changed since the last grab/poll, else null
     * (MediaProjection only delivers buffers on change). [Frame.atMs] is wall-clock arrival time.
     */
    fun pollNewFrame(): Frame? {
        if (!isReady) return null
        synchronized(this) {
            val image = heldImage ?: return null
            val bitmap = image.toBitmapSafe()
            image.close()
            heldImage = null
            lastFrameBitmap = bitmap
            val ageMs = SystemClock.elapsedRealtime() - lastFrameAt
            return Frame(bitmap, System.currentTimeMillis() - ageMs)
        }
    }

    fun cropToRegion(frame: Bitmap, region: RectFrac): Bitmap {
        region.clamp()
        val left = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (region.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (region.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }

    /** A capture region expressed as fractions of the screen, robust to rotation. */
    data class RectFrac(
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
    ) {
        fun clamp() {
            left = left.coerceIn(0f, 1f)
            top = top.coerceIn(0f, 1f)
            right = right.coerceIn(0f, 1f)
            bottom = bottom.coerceIn(0f, 1f)
            if (right <= left) right = (left + 0.2f).coerceAtMost(1f)
            if (bottom <= top) bottom = (top + 0.2f).coerceAtMost(1f)
        }

        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun toRect(screenW: Int, screenH: Int): Rect = Rect(
            (left * screenW).toInt(),
            (top * screenH).toInt(),
            (right * screenW).toInt(),
            (bottom * screenH).toInt(),
        )
    }

    companion object {
        fun screenMetrics(context: Context): DisplayMetrics {
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.getSystemService(WindowManager::class.java)
                val bounds = wm.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = context.resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
            }
            return metrics
        }
    }
}

/**
 * Converts an ImageReader frame (RGBA_8888) into a Bitmap, respecting row padding.
 */
private fun Image.toBitmapSafe(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + if (pixelStride > 0) rowPadding / pixelStride else 0
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    padded.copyPixelsFromBuffer(buffer)
    if (rowPadding == 0) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}
