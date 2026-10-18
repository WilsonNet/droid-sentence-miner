package io.github.wilsonnet.droidsentenceminer

/** Whether a DSM screen is in the foreground, so auto OCR doesn't log our own UI. */
object AppVisibility {
    @Volatile
    var mainResumed: Boolean = false
}
