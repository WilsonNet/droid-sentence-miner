package io.github.wilsonnet.droidsentenceminer.core

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * The app's FileProvider, with a type for our AAC clips. AnkiDroid names stored media from the
 * shared file's MIME type, and Android's table maps `.m4a` to `audio/mpeg` (→ `.mp3`), so cards
 * got `…_<n>.mp3` files holding AAC.
 */
class DsmFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".m4a", ignoreCase = true) == true) "audio/mp4" else super.getType(uri)
}
