package io.github.wilsonnet.droidsentenceminer.ui.popup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.MainActivity
import java.io.File

/**
 * Share sheet → Book: copies the shared photo into the cache while the sender's read grant is
 * valid, hands it to the Book tab ([AppContainer.sharedBookPhoto]) and opens the app. Sharing to
 * MainActivity itself did nothing from HyperOS's gallery: the singleTask activity in its own task
 * never came forward over the share sheet.
 */
class SharePhotoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedUri(intent)
        val copy = uri?.let { copyToCache(it) }
        if (copy == null) {
            Toast.makeText(this, "Could not open the shared photo", Toast.LENGTH_SHORT).show()
        } else {
            AppContainer.from(this).sharedBookPhoto.value = Uri.fromFile(copy)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun copyToCache(uri: Uri): File? = runCatching {
        val file = File(cacheDir, "shared/page").apply { parentFile?.mkdirs() }
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        file
    }.onFailure { android.util.Log.w("DSM-Book", "Could not copy shared $uri", it) }.getOrNull()
}
