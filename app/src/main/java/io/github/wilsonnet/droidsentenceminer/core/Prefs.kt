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
        val audioEnabled = booleanPreferencesKey("audio_enabled")

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
    val audioEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.audioEnabled] ?: false }

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
    suspend fun setAudioEnabled(value: Boolean) = edit { it[Keys.audioEnabled] = value }

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
    }
}
