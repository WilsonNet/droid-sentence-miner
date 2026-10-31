package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.anki.CardEdits
import io.github.wilsonnet.droidsentenceminer.audio.AudioPcm
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.VoiceTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the card editor opens with: the [word] being mined, its [sentence], the sentence audio
 * ([loadAudio], null when the source has none) and where the edited card goes ([submit]).
 */
class CardEditRequest(
    val word: String,
    val sentence: String,
    val loadAudio: (suspend () -> AudioSegment?)?,
    val submit: (CardEdits) -> Unit,
)

/**
 * Fine-tuning before a card is added: trim the sentence (type, or keep one of its sentences with a
 * tap, no keyboard needed) and the sentence audio. Hosted in a dialog in the app and in a focusable
 * overlay window over other apps ([io.github.wilsonnet.droidsentenceminer.overlay.ScreenDictionary]).
 */
@Composable
fun CardEditor(request: CardEditRequest, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Over a game the popup's text is the screen's rows joined by line breaks: those are wraps,
    // and the logged line (what an unedited card gets) has none.
    val original = remember(request) { request.sentence.replace("\n", "") }
    var sentence by remember(request) { mutableStateOf(original) }
    var audio by remember(request) { mutableStateOf<AudioSegment?>(null) }
    var audioLoading by remember(request) { mutableStateOf(request.loadAudio != null) }
    var includeAudio by remember(request) { mutableStateOf(true) }
    /** The clip holds no voice (unvoiced lines: a visual novel's heroine, narration). */
    var silent by remember(request) { mutableStateOf(false) }
    var range by remember(request) { mutableStateOf(0f..0f) }
    LaunchedEffect(request) {
        val loaded = runCatching { request.loadAudio?.invoke() }.getOrNull()
        // Handles start on the voice: a line left on screen while you read runs on as room noise or,
        // through the microphone fallback, as the game's music, which the default threshold keeps.
        val voice = loaded?.let { withContext(Dispatchers.Default) { VoiceTrimmer.trim(it, relativeThreshold = VOICE_THRESHOLD) } }
        audio = loaded
        silent = loaded != null && AudioPcm.isSilent(loaded)
        if (silent) includeAudio = false
        range = when {
            voice?.trimmed == true -> voice.startMs.toFloat()..voice.endMs.toFloat()
            else -> 0f..(loaded?.durationMs?.toFloat() ?: 0f)
        }
        audioLoading = false
    }
    val parts = remember(request) { sentencesOf(original) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Edit card", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(
                    request.word,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionLabel("Sentence") {
                if (sentence != original) {
                    TextButton(onClick = { sentence = original }) { Text("Reset") }
                }
            }
            OutlinedTextField(
                value = sentence,
                onValueChange = { sentence = it },
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                minLines = 2,
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = remember(request.word) { HighlightWord(request.word) },
            )
            if (parts.size > 1) {
                // One tap keeps one sentence of a multi-sentence line.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    parts.forEach { part ->
                        SuggestionChip(
                            onClick = { sentence = part },
                            label = { Text(part.ellipsize(24), maxLines = 1) },
                        )
                    }
                }
            }

            SectionLabel("Sentence audio") {
                if (audio != null) Switch(checked = includeAudio, onCheckedChange = { includeAudio = it })
            }
            val segment = audio
            when {
                audioLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Cutting the clip…", style = MaterialTheme.typography.bodyMedium)
                }
                segment == null -> Text(
                    "No sentence audio for this card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                includeAudio -> AudioTrimmer(segment, range, onRangeChange = { range = it })
                else -> Text(
                    if (silent) "The clip is silent (an unvoiced line?): the card goes without sentence audio." else "The card goes without sentence audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !audioLoading && sentence.isNotBlank(),
                    onClick = {
                        val clip = segment?.takeIf { includeAudio }?.let { trimmed(it, range) ?: it }
                        request.submit(CardEdits(sentence.trim(), clip))
                        onClose()
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to Anki")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * The sentences of a line, ends kept (。！？ and their closing quotes), for the trim chips. A quote
 * split across sentences loses its unmatched half: 「だから…しません。 → だから…しません。
 */
internal fun sentencesOf(text: String): List<String> =
    // A line break is where the game wrapped the text, not where a sentence ends.
    Regex("[^。！？!?]+[。！？!?…]*[」』）)]*").findAll(text)
        .map { balanceQuotes(it.value.replace("\n", "").trim()) }
        .filter { it.isNotEmpty() }
        .toList()

private val QUOTE_PAIRS = listOf('「' to '」', '『' to '』', '（' to '）', '(' to ')')

private fun balanceQuotes(part: String): String {
    var s = part
    for ((open, close) in QUOTE_PAIRS) {
        if (s.startsWith(open) && close !in s) s = s.drop(1)
        if (s.endsWith(close) && open !in s) s = s.dropLast(1)
    }
    return s.trim()
}

/**
 * [VoiceTrimmer]'s threshold for the editor's suggested range: measured on two Nightshade
 * microphone clips, 0.05 kept 11 s of background music after a 4.5 s line, 0.15 ends 0.5 s
 * after the voice on both.
 */
private const val VOICE_THRESHOLD = 0.15

private fun String.ellipsize(max: Int) = if (length <= max) this else take(max - 1) + "…"

/** Bolds the mined word in the sentence field, as it will be on the card. */
private class HighlightWord(private val word: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (word.isBlank()) return TransformedText(text, OffsetMapping.Identity)
        val start = text.text.indexOf(word)
        if (start < 0) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + word.length)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
