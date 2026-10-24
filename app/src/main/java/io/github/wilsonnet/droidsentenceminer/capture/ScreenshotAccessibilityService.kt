package io.github.wilsonnet.droidsentenceminer.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Screenshots without a screen-capture consent dialog: an accessibility service is switched on
 * once in system settings and stays on, while MediaProjection asks again every session (Android 14+
 * never lets an app keep the consent). Quick lookups and one-off scans use it; auto OCR still
 * needs MediaProjection because it also records the playback audio.
 *
 * It reads no window content and handles no events; it only takes a screenshot when asked.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The whole display as a software bitmap, or null (secure window, rate limit, error). */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    cont.resume(null)
                }
            },
        )
    }

    companion object {
        @Volatile
        private var instance: ScreenshotAccessibilityService? = null

        /** Screenshots need API 30; below that the service is useless. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /** Switched on in settings and bound right now. */
        val isConnected: Boolean get() = supported && instance != null

        suspend fun screenshot(): Bitmap? {
            if (!supported) return null
            val service = instance ?: return null
            return service.capture()
        }

        /** Whether the user switched it on (it may take a moment to bind after that). */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, ScreenshotAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
