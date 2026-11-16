package io.github.wilsonnet.droidsentenceminer.manga

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide state of the manga OCR model download ([MangaOcrModels]), so the progress survives
 * closing the reader settings sheet.
 */
object MangaModelDownloads {
    /** Download state shown by the settings UI. */
    data class State(
        /** True when every model file is present. */
        val isInstalled: Boolean = false,
        /** Progress 0..100 while downloading, else null. */
        val progress: Int? = null,
        /** Last failure, cleared by the next attempt. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())

    /** Current download state. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Re-reads whether the models are installed. */
    fun refresh(context: Context) {
        if (_state.value.progress == null) _state.value = _state.value.copy(isInstalled = MangaOcrModels.isInstalled(context))
    }

    /** Starts downloading the models on [scope] unless a download is already running. */
    fun start(context: Context, scope: CoroutineScope) {
        if (_state.value.progress != null) return
        _state.value = State(isInstalled = false, progress = 0)
        scope.launch {
            val error =
                MangaOcrModels.download(context = context) { percent ->
                    _state.value = _state.value.copy(progress = percent)
                }
            _state.value = State(isInstalled = MangaOcrModels.isInstalled(context), progress = null, error = error)
        }
    }

    /** Deletes the models and unloads them. */
    fun remove(context: Context) {
        MangaLocalOcr.unload()
        MangaOcrModels.remove(context)
        _state.value = State(isInstalled = false)
    }
}
