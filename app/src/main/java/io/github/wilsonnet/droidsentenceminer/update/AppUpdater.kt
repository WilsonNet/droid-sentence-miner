package io.github.wilsonnet.droidsentenceminer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.wilsonnet.droidsentenceminer.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A published GitHub release with its signed APK (`.github/workflows/release.yml`). */
data class AppRelease(val version: String, val tag: String, val apkUrl: String, val apkSize: Long, val notes: String, val pageUrl: String)

/**
 * Release builds check GitHub Releases for a newer `v*` tag and update in one tap: the APK is
 * downloaded into the cache and handed to the system installer (same signing key, so it installs
 * over this one and keeps all data). Debug builds never check: their version is not a release's.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: io.github.wilsonnet.droidsentenceminer.core.DsmPrefs,
) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val progress: Float?) : State
        /** Downloaded; the installer was opened (or needs the "install unknown apps" permission first). */
        data class Ready(val release: AppRelease, val apk: File) : State
        data class Failed(val message: String, val release: AppRelease?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var checkedThisRun = false

    /** Home's automatic check: once per process, release builds only, unless turned off in Settings → About. */
    fun checkOnce() {
        if (!ENABLED || checkedThisRun) return
        checkedThisRun = true
        scope.launch { if (prefs.appUpdateCheck.first()) check() }
    }

    fun check() {
        val current = _state.value
        if (current is State.Checking || current is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                val release = latest()
                when {
                    release == null -> State.Failed("No release APK found on GitHub", null)
                    AppVersion.isNewer(BuildConfig.VERSION_NAME, release.version) -> State.Available(release)
                    else -> State.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed("Could not check for updates: ${e.message}", null)
            }
        }
    }

    /** Downloads [release] (unless already downloaded) and opens the system installer. */
    fun update(release: AppRelease) {
        val current = _state.value
        if (current is State.Downloading) return
        if (current is State.Ready && current.release == release && current.apk.exists()) {
            install(current.apk)
            return
        }
        _state.value = State.Downloading(release, null)
        scope.launch {
            _state.value = try {
                val apk = download(release)
                install(apk)
                State.Ready(release, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed("Download failed: ${e.message}", release)
            }
        }
    }

    private suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DroidSentenceMiner/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub answered HTTP ${response.code}")
            parseRelease(response.body?.string().orEmpty())
        }
    }

    private suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // Only the newest download is kept.
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "droid-sentence-miner-${release.tag}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "DroidSentenceMiner").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
                var done = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                _state.value = State.Downloading(release, total?.let { done.toFloat() / it })
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
        apk
    }

    /**
     * Hands [apk] to the system installer. Without the "install unknown apps" permission the
     * system's page for it opens instead; tapping Update again afterwards installs.
     */
    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        /** Debug builds are versioned 0.1.0 locally and signed with another key: never offer them a release. */
        val ENABLED = !BuildConfig.DEBUG

        const val LATEST_RELEASE_URL = "https://api.github.com/repos/WilsonNet/droid-sentence-miner/releases/latest"
        private const val UPDATE_DIR = "updates"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** GitHub's `releases/latest` JSON → the release and its `.apk` asset (null without one). */
        fun parseRelease(json: String): AppRelease? {
            val root = JSONObject(json)
            val tag = root.optString("tag_name").ifBlank { return null }
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return AppRelease(
                        version = tag.removePrefix("v"),
                        tag = tag,
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size"),
                        notes = root.optString("body"),
                        pageUrl = root.optString("html_url"),
                    )
                }
            }
            return null
        }
    }
}

/** Semver-ish comparison of release versions (`0.4.0` < `0.4.1` < `0.10.0`; a pre-release suffix sorts first). */
object AppVersion {
    fun isNewer(installed: String, remote: String): Boolean = compare(remote, installed) > 0

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val diff = (coreA.getOrNull(i) ?: 0).compareTo(coreB.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return when {
            preA == preB -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    private fun split(version: String): Pair<List<Int>, String?> {
        val v = version.trim().removePrefix("v")
        val core = v.substringBefore('-').substringBefore('+')
        val pre = v.substringAfter('-', "").substringBefore('+').ifBlank { null }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
