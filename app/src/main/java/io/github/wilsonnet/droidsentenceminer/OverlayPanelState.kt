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

    /** Text-log line the panel is showing; mining attaches its screenshot and voice clip. */
    var lineId by mutableStateOf<Long?>(null)
    var autoOcrRunning by mutableStateOf(false)

    /** Auto OCR is on but the screen capture ended (consent revoked, captured app closed). */
    var captureStopped by mutableStateOf(false)

    /** Microphone fallback setting, toggled from the bubble menu too. */
    var micFallback by mutableStateOf(false)

    // Audio (GSM-style sentence audio)
    var audioSegment by mutableStateOf<AudioSegment?>(null)
    var audioSkipped by mutableStateOf(false)
    var audioEditorVisible by mutableStateOf(false)
    var audioReady by mutableStateOf(false)
}
