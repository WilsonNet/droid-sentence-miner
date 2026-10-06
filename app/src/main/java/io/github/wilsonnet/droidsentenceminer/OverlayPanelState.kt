package io.github.wilsonnet.droidsentenceminer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup

enum class PanelMode { MENU, RESULT }

/** Observable state shared between OverlayService and the Compose panel. */
class OverlayPanelState {
    var mode by mutableStateOf(PanelMode.MENU)
    var scanning by mutableStateOf(false)
    var ocrText by mutableStateOf("")
    var matches by mutableStateOf<List<ScannedWord>>(emptyList())
    var selectedWord by mutableStateOf<String?>(null)
    var selectedDetails by mutableStateOf<TermLookup.LookupDetails?>(null)
    var definitionsHtml by mutableStateOf<String?>(null)
    var loadingDetails by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
    var imagePreview by mutableStateOf<Bitmap?>(null)

    // Audio (GSM-style sentence audio)
    var audioSegment by mutableStateOf<AudioSegment?>(null)
    var audioEditorVisible by mutableStateOf(false)
    var audioReady by mutableStateOf(false)
}
