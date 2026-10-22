package io.github.wilsonnet.droidsentenceminer.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dsm_settings")

class DsmPrefs(private val context: Context) {

    private object Keys {
        val ankiProvider = stringPreferencesKey("anki_provider")
        val ankiConnectUrl = stringPreferencesKey("anki_connect_url")
        val ankiDeck = stringPreferencesKey("anki_deck")
        val ankiModel = stringPreferencesKey("anki_model")
        val ankiTags = stringPreferencesKey("anki_tags")
        val ankiIncludeImage = booleanPreferencesKey("anki_include_image")
        val ankiAnkiDroidPermissionRequested = booleanPreferencesKey("anki_permission_requested")

        val ocrTwoPass = booleanPreferencesKey("ocr_two_pass")
        val ocrDropNoise = booleanPreferencesKey("ocr_drop_noise")
        val ocrEngine = stringPreferencesKey("ocr_engine")
        val audioEnabled = booleanPreferencesKey("audio_enabled")
        val audioTrim = booleanPreferencesKey("audio_trim")
        val wordAudio = booleanPreferencesKey("word_audio")
        val glossaryFirstDictionary = booleanPreferencesKey("glossary_first_dictionary")
        val wordAudioCustomUrl = stringPreferencesKey("word_audio_custom_url")
        val audioBufferSeconds = intPreferencesKey("audio_buffer_seconds")
        val clipBeforeMs = intPreferencesKey("clip_before_ms")
        val clipAfterMs = intPreferencesKey("clip_after_ms")
        val autoOcrIntervalMs = intPreferencesKey("auto_ocr_interval_ms")
        val autoAreasPortrait = stringPreferencesKey("auto_areas_portrait")
        val autoAreasLandscape = stringPreferencesKey("auto_areas_landscape")
        val liveTextWindow = booleanPreferencesKey("live_text_window")
        val screenDictionary = booleanPreferencesKey("screen_dictionary")
        val pauseOnLookup = booleanPreferencesKey("pause_on_lookup")
        val liveWindowX = intPreferencesKey("live_window_x")
        val liveWindowY = intPreferencesKey("live_window_y")
        val liveWindowMode = stringPreferencesKey("live_window_mode")
        val liveWindowWidthDp = intPreferencesKey("live_window_width_dp")
        val liveWindowHeightDp = intPreferencesKey("live_window_height_dp")
        val liveWindowXLand = intPreferencesKey("live_window_x_land")
        val liveWindowYLand = intPreferencesKey("live_window_y_land")
        val liveWindowWidthDpLand = intPreferencesKey("live_window_width_dp_land")
        val liveWindowHeightDpLand = intPreferencesKey("live_window_height_dp_land")

        val captureLeft = floatPreferencesKey("capture_left")
        val captureTop = floatPreferencesKey("capture_top")
        val captureRight = floatPreferencesKey("capture_right")
        val captureBottom = floatPreferencesKey("capture_bottom")

        val bubbleX = intPreferencesKey("bubble_x")
        val bubbleY = intPreferencesKey("bubble_y")

        val overlayRunning = booleanPreferencesKey("overlay_running")
        val lastScan = stringPreferencesKey("last_scan")
    }

    val ankiProvider: Flow<String> = context.dataStore.data.map { it[Keys.ankiProvider] ?: PROVIDER_ANKIDROID }
    val ankiConnectUrl: Flow<String> = context.dataStore.data.map { it[Keys.ankiConnectUrl] ?: DEFAULT_CONNECT_URL }
    val ankiDeck: Flow<String> = context.dataStore.data.map { it[Keys.ankiDeck] ?: DEFAULT_DECK }
    val ankiModel: Flow<String> = context.dataStore.data.map { it[Keys.ankiModel] ?: DEFAULT_MODEL }
    val ankiTags: Flow<String> = context.dataStore.data.map { it[Keys.ankiTags] ?: DEFAULT_TAGS }
    val ankiIncludeImage: Flow<Boolean> = context.dataStore.data.map { it[Keys.ankiIncludeImage] ?: true }
    val ankiPermissionRequested: Flow<Boolean> = context.dataStore.data.map { it[Keys.ankiAnkiDroidPermissionRequested] ?: false }

    val ocrTwoPass: Flow<Boolean> = context.dataStore.data.map { it[Keys.ocrTwoPass] ?: true }
    val ocrDropNoise: Flow<Boolean> = context.dataStore.data.map { it[Keys.ocrDropNoise] ?: true }

    /** [ENGINE_MEIKI] (game-trained, needs a model download) or [ENGINE_MLKIT]. */
    val ocrEngine: Flow<String> = context.dataStore.data.map { it[Keys.ocrEngine] ?: ENGINE_MEIKI }
    val audioEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.audioEnabled] ?: true }
    // Off by default: energy VAD can't separate voice from loud BGM/ambience reliably.
    val audioTrim: Flow<Boolean> = context.dataStore.data.map { it[Keys.audioTrim] ?: false }
    val wordAudio: Flow<Boolean> = context.dataStore.data.map { it[Keys.wordAudio] ?: true }

    /** Like Yomitan's {glossary-first}: only the highest-priority dictionary on the card. */
    val glossaryFirstDictionary: Flow<Boolean> = context.dataStore.data.map { it[Keys.glossaryFirstDictionary] ?: false }

    /** Yomitan-style custom audio URL with {term} and {reading}; tried before the built-in sources. */
    val wordAudioCustomUrl: Flow<String> = context.dataStore.data.map { it[Keys.wordAudioCustomUrl] ?: "" }
    val audioBufferSeconds: Flow<Int> = context.dataStore.data.map { it[Keys.audioBufferSeconds] ?: DEFAULT_AUDIO_BUFFER_SECONDS }
    val clipBeforeMs: Flow<Int> = context.dataStore.data.map { it[Keys.clipBeforeMs] ?: 500 }
    val clipAfterMs: Flow<Int> = context.dataStore.data.map { it[Keys.clipAfterMs] ?: 500 }
    val autoOcrIntervalMs: Flow<Int> = context.dataStore.data.map { it[Keys.autoOcrIntervalMs] ?: 400 }
    val liveTextWindow: Flow<Boolean> = context.dataStore.data.map { it[Keys.liveTextWindow] ?: true }
    /** Live text window position in px, per orientation (a portrait spot is wrong in landscape). */
    fun liveWindowPosition(landscape: Boolean): Flow<Pair<Int, Int>?> = context.dataStore.data.map {
        val x = it[if (landscape) Keys.liveWindowXLand else Keys.liveWindowX] ?: return@map null
        val y = it[if (landscape) Keys.liveWindowYLand else Keys.liveWindowY] ?: return@map null
        x to y
    }

    fun autoAreas(landscape: Boolean): Flow<List<io.github.wilsonnet.droidsentenceminer.capture.TextArea>> =
        context.dataStore.data.map {
            io.github.wilsonnet.droidsentenceminer.capture.TextArea.decode(
                it[if (landscape) Keys.autoAreasLandscape else Keys.autoAreasPortrait],
            )
        }

    suspend fun setAutoAreas(landscape: Boolean, areas: List<io.github.wilsonnet.droidsentenceminer.capture.TextArea>) = edit {
        it[if (landscape) Keys.autoAreasLandscape else Keys.autoAreasPortrait] =
            io.github.wilsonnet.droidsentenceminer.capture.TextArea.encode(areas)
    }

    suspend fun setLiveTextWindow(value: Boolean) = edit { it[Keys.liveTextWindow] = value }

    /** Tap the real on-screen text (over any app) to look it up while auto OCR runs. */
    val screenDictionary: Flow<Boolean> = context.dataStore.data.map { it[Keys.screenDictionary] ?: true }
    suspend fun setScreenDictionary(value: Boolean) = edit { it[Keys.screenDictionary] = value }

    /** Pause the playing video while a screen-dictionary lookup is open. */
    val pauseOnLookup: Flow<Boolean> = context.dataStore.data.map { it[Keys.pauseOnLookup] ?: true }
    suspend fun setPauseOnLookup(value: Boolean) = edit { it[Keys.pauseOnLookup] = value }
    suspend fun setLiveWindowPosition(landscape: Boolean, x: Int, y: Int) = edit {
        it[if (landscape) Keys.liveWindowXLand else Keys.liveWindowX] = x
        it[if (landscape) Keys.liveWindowYLand else Keys.liveWindowY] = y
    }

    /** Live text window mode: an `overlay.LiveMode` name (compact, normal, fullscreen). */
    val liveWindowMode: Flow<String?> = context.dataStore.data.map { it[Keys.liveWindowMode] }
    suspend fun setLiveWindowMode(value: String) = edit { it[Keys.liveWindowMode] = value }

    /** Live text window size in dp (normal mode; compact keeps the width), per orientation. */
    fun liveWindowSizeDp(landscape: Boolean): Flow<Pair<Int, Int>?> = context.dataStore.data.map {
        val w = it[if (landscape) Keys.liveWindowWidthDpLand else Keys.liveWindowWidthDp] ?: return@map null
        val h = it[if (landscape) Keys.liveWindowHeightDpLand else Keys.liveWindowHeightDp] ?: return@map null
        w to h
    }
    suspend fun setLiveWindowSizeDp(landscape: Boolean, width: Int, height: Int) = edit {
        it[if (landscape) Keys.liveWindowWidthDpLand else Keys.liveWindowWidthDp] = width
        it[if (landscape) Keys.liveWindowHeightDpLand else Keys.liveWindowHeightDp] = height
    }

    val captureRegion: Flow<CaptureRegion> = context.dataStore.data.map {
        CaptureRegion(
            left = it[Keys.captureLeft] ?: 0.06f,
            top = it[Keys.captureTop] ?: 0.62f,
            right = it[Keys.captureRight] ?: 0.94f,
            bottom = it[Keys.captureBottom] ?: 0.82f,
        )
    }

    val bubblePosition: Flow<Pair<Int, Int>?> = context.dataStore.data.map {
        val x = it[Keys.bubbleX] ?: return@map null
        val y = it[Keys.bubbleY] ?: return@map null
        x to y
    }

    val overlayRunning: Flow<Boolean> = context.dataStore.data.map { it[Keys.overlayRunning] ?: false }
    val lastScanText: Flow<String> = context.dataStore.data.map { it[Keys.lastScan] ?: "" }

    suspend fun setAnkiProvider(value: String) = edit { it[Keys.ankiProvider] = value }
    suspend fun setAnkiConnectUrl(value: String) = edit { it[Keys.ankiConnectUrl] = value.trim() }
    suspend fun setAnkiDeck(value: String) = edit { it[Keys.ankiDeck] = value.trim() }
    suspend fun setAnkiModel(value: String) = edit { it[Keys.ankiModel] = value.trim() }
    suspend fun setAnkiTags(value: String) = edit { it[Keys.ankiTags] = value }
    suspend fun setAnkiIncludeImage(value: Boolean) = edit { it[Keys.ankiIncludeImage] = value }
    suspend fun setAnkiPermissionRequested(value: Boolean) = edit { it[Keys.ankiAnkiDroidPermissionRequested] = value }

    suspend fun setOcrTwoPass(value: Boolean) = edit { it[Keys.ocrTwoPass] = value }
    suspend fun setOcrDropNoise(value: Boolean) = edit { it[Keys.ocrDropNoise] = value }
    suspend fun setOcrEngine(value: String) = edit { it[Keys.ocrEngine] = value }
    suspend fun setAudioEnabled(value: Boolean) = edit { it[Keys.audioEnabled] = value }
    suspend fun setAudioTrim(value: Boolean) = edit { it[Keys.audioTrim] = value }
    suspend fun setWordAudio(value: Boolean) = edit { it[Keys.wordAudio] = value }
    suspend fun setGlossaryFirstDictionary(value: Boolean) = edit { it[Keys.glossaryFirstDictionary] = value }
    suspend fun setWordAudioCustomUrl(value: String) = edit { it[Keys.wordAudioCustomUrl] = value.trim() }
    suspend fun setAudioBufferSeconds(value: Int) = edit { it[Keys.audioBufferSeconds] = value }
    suspend fun setClipBeforeMs(value: Int) = edit { it[Keys.clipBeforeMs] = value }
    suspend fun setClipAfterMs(value: Int) = edit { it[Keys.clipAfterMs] = value }
    suspend fun setAutoOcrIntervalMs(value: Int) = edit { it[Keys.autoOcrIntervalMs] = value }

    suspend fun setCaptureRegion(region: CaptureRegion) = edit {
        it[Keys.captureLeft] = region.left
        it[Keys.captureTop] = region.top
        it[Keys.captureRight] = region.right
        it[Keys.captureBottom] = region.bottom
    }

    suspend fun setBubblePosition(x: Int, y: Int) = edit {
        it[Keys.bubbleX] = x
        it[Keys.bubbleY] = y
    }

    suspend fun setOverlayRunning(value: Boolean) = edit { it[Keys.overlayRunning] = value }
    suspend fun setLastScanText(value: String) = edit { it[Keys.lastScan] = value }

    suspend fun snapshot(): Snapshot = Snapshot(
        ankiProvider = ankiProvider.first(),
        ankiConnectUrl = ankiConnectUrl.first(),
        ankiDeck = ankiDeck.first(),
        ankiModel = ankiModel.first(),
        ankiTags = ankiTags.first(),
        ankiIncludeImage = ankiIncludeImage.first(),
        ocrTwoPass = ocrTwoPass.first(),
        ocrDropNoise = ocrDropNoise.first(),
        captureRegion = captureRegion.first(),
    )

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    data class CaptureRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    data class Snapshot(
        val ankiProvider: String,
        val ankiConnectUrl: String,
        val ankiDeck: String,
        val ankiModel: String,
        val ankiTags: String,
        val ankiIncludeImage: Boolean,
        val ocrTwoPass: Boolean,
        val ocrDropNoise: Boolean,
        val captureRegion: CaptureRegion,
    )

    companion object {
        const val PROVIDER_ANKIDROID = "ankidroid"
        const val PROVIDER_ANKICONNECT = "ankiconnect"
        const val DEFAULT_CONNECT_URL = "http://127.0.0.1:8765"
        const val DEFAULT_DECK = "Droid Sentence Miner"
        const val DEFAULT_MODEL = "Droid Sentence Miner"
        const val DEFAULT_TAGS = "DroidSentenceMiner"
        const val DEFAULT_AUDIO_BUFFER_SECONDS = 300
        const val ENGINE_MEIKI = "meiki"
        const val ENGINE_MLKIT = "mlkit"
    }
}
