package io.github.wilsonnet.droidsentenceminer.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import io.github.wilsonnet.droidsentenceminer.R

/**
 * Tiny transparent activity used to request screen-capture consent.
 * The result is forwarded to [OverlayService], which owns the projection.
 */
class ProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection_permission)

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_PROJECTION_RESULT
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 7301
    }
}
