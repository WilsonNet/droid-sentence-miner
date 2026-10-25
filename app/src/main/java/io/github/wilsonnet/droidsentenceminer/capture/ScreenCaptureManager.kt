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

    /**
     * Last frame whose text regions were already checked by [pollRegionCrops], kept as an [Image]
     * so static frames don't allocate a full-screen [Bitmap] unless a line actually starts or
     * [grabFrame] is called. At most one [Image] is held across [heldImage] and [retainedImage].
     */
    @Volatile
    private var retainedImage: Image? = null

    @Volatile
    private var lastFrameBitmap: Bitmap? = null

    @Volatile
    private var lastFrameAt: Long = 0L

    @Volatile
    private var acquireScheduled: Boolean = false

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
        // The size from service start, or the last rotation while capturing, can be stale: rotating
        // the tablet before (re)starting capture produced portrait frames of a landscape game.
        val metrics = screenMetrics(context)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
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

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
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
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener(::onImageAvailable, h)
        val old = imageReader
        display.resize(screenWidth, screenHeight, densityDpi)
        display.surface = reader.surface
        imageReader = reader
        synchronized(this) {
            runCatching { heldImage?.close() }
            heldImage = null
            runCatching { retainedImage?.close() }
            retainedImage = null
            lastFrameBitmap = null
        }
        runCatching { old?.close() }
        return true
    }

    private fun onImageAvailable(reader: ImageReader) {
        // Games (DroidDeck/gamescope) flip at 60–144 Hz; acquiring on every VSYNC frees the
        // BufferQueue slot immediately and makes SurfaceFlinger composite 144 full-screen frames/s.
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_ACQUIRE_INTERVAL_MS - (now - lastFrameAt)
        if (wait > 0) {
            if (!acquireScheduled) {
                acquireScheduled = true
                handler?.postDelayed({
                    acquireScheduled = false
                    synchronized(this) { acquireLatestLocked() }
                }, wait)
            }
            return
        }
        synchronized(this) { acquireLatestLocked(reader) }
    }

    private fun acquireLatestLocked(reader: ImageReader? = imageReader) {
        val r = reader ?: return
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        runCatching { heldImage?.close() }
        runCatching { retainedImage?.close() }
        retainedImage = null
        heldImage = image
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    fun release() {
        isReady = false
        contentVisible = true
        acquireScheduled = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        synchronized(this) {
            runCatching { heldImage?.close() }
            heldImage = null
            runCatching { retainedImage?.close() }
            retainedImage = null
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
            val bitmap = synchronized(this) {
                acquireLatestLocked()
                val fresh = lastFrameAt > 0 && SystemClock.elapsedRealtime() - lastFrameAt <= maxAgeMs
                if (!fresh) null
                else (heldImage ?: retainedImage)?.let { img ->
                    val bmp = img.toBitmapSafe()
                    img.close()
                    heldImage = null
                    retainedImage = null
                    bmp
                }
            }
            if (bitmap != null) {
                lastFrameBitmap = bitmap
                return bitmap
            }
            delay(32)
        }
        // A static screen produces no new buffers, so fall back to the last frame we saw.
        synchronized(this) {
            acquireLatestLocked()
            (heldImage ?: retainedImage)?.let { img ->
                val bmp = img.toBitmapSafe()
                img.close()
                heldImage = null
                retainedImage = null
                lastFrameBitmap = bmp
            }
        }
        return lastFrameBitmap
    }

    data class Frame(val bitmap: Bitmap, val atMs: Long)

    data class RegionPoll(
        val changed: Boolean,
        val signature: IntArray,
        val crops: List<Bitmap>,
        val atMs: Long,
    )

    /**
     * Non-blocking: the newest frame if the screen changed since the last grab/poll, else null
     * (MediaProjection only delivers buffers on change). [Frame.atMs] is wall-clock arrival time.
     */
    fun pollNewFrame(): Frame? {
        if (!isReady) return null
        synchronized(this) {
            acquireLatestLocked()
            val image = heldImage ?: return null
            val bitmap = image.toBitmapSafe()
            image.close()
            heldImage = null
            runCatching { retainedImage?.close() }
            retainedImage = null
            lastFrameBitmap = bitmap
            val ageMs = SystemClock.elapsedRealtime() - lastFrameAt
            return Frame(bitmap, System.currentTimeMillis() - ageMs)
        }
    }

    /**
     * Checks whether [regions] visibly changed since [lastSignature] directly on the [Image] plane
     * without allocating a full-screen [Bitmap], and crops only [regions] when they did change.
     */
    fun pollRegionCrops(regions: List<RectFrac>, lastSignature: IntArray?): RegionPoll? {
        if (!isReady || regions.isEmpty()) return null
        synchronized(this) {
            acquireLatestLocked()
            val image = heldImage ?: return null
            val ageMs = SystemClock.elapsedRealtime() - lastFrameAt
            val atMs = System.currentTimeMillis() - ageMs
            val signature = regions.map { FrameSignature.of(image, it) }.reduce { acc, sig -> acc + sig }
            val changed = lastSignature == null || FrameSignature.changed(signature, lastSignature)
            val crops = if (changed) regions.map { image.cropRegion(it) } else emptyList()
            heldImage = null
            runCatching { retainedImage?.close() }
            retainedImage = image
            return RegionPoll(changed = changed, signature = signature, crops = crops, atMs = atMs)
        }
    }

    /** Full-screen screenshot of the latest frame (converts [retainedImage] on demand once per line). */
    fun latestScreenshot(): Bitmap? {
        if (!isReady) return null
        synchronized(this) {
            acquireLatestLocked()
            (heldImage ?: retainedImage)?.let { img ->
                val bmp = img.toBitmapSafe()
                img.close()
                heldImage = null
                retainedImage = null
                lastFrameBitmap = bmp
            }
            return lastFrameBitmap
        }
    }

    /** Drops any pending frame without converting it to a [Bitmap] (used while auto OCR is paused). */
    fun discardNewFrame() {
        if (!isReady) return
        synchronized(this) {
            acquireLatestLocked()
            runCatching { heldImage?.close() }
            heldImage = null
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
        private const val MIN_ACQUIRE_INTERVAL_MS = 100L

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
 * Crops [region] directly from an RGBA_8888 [Image] plane without copying the rest of the screen.
 */
private fun Image.cropRegion(region: ScreenCaptureManager.RectFrac): Bitmap {
    region.clamp()
    val left = (region.left * width).toInt().coerceIn(0, width - 1)
    val top = (region.top * height).toInt().coerceIn(0, height - 1)
    val right = (region.right * width).toInt().coerceIn(left + 1, width)
    val bottom = (region.bottom * height).toInt().coerceIn(top + 1, height)
    val cropW = right - left
    val cropH = bottom - top

    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val paddedWidth = if (pixelStride > 0) rowStride / pixelStride else width
    val startByte = top * rowStride
    val endByte = startByte + cropH * rowStride
    if (pixelStride <= 0 || endByte > plane.buffer.capacity()) {
        val full = toBitmapSafe()
        val cropped = Bitmap.createBitmap(full, left, top, cropW, cropH)
        if (cropped !== full) full.recycle()
        return cropped
    }
    val slice = plane.buffer.duplicate().apply {
        position(startByte)
        limit(endByte)
    }.slice()
    val band = Bitmap.createBitmap(paddedWidth, cropH, Bitmap.Config.ARGB_8888)
    band.copyPixelsFromBuffer(slice)
    if (left == 0 && cropW == paddedWidth) return band
    val cropped = Bitmap.createBitmap(band, left, 0, cropW, cropH)
    band.recycle()
    return cropped
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
