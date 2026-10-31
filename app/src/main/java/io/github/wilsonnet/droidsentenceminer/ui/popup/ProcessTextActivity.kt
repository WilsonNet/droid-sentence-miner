package io.github.wilsonnet.droidsentenceminer.ui.popup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupDialog
import io.github.wilsonnet.droidsentenceminer.ui.theme.DsmTheme
import kotlinx.coroutines.launch

/**
 * Receives text shared from other apps via ACTION_PROCESS_TEXT and shows
 * a dictionary lookup popup.
 */
class ProcessTextActivity : ComponentActivity() {

    private companion object {
        const val SENTENCE_MARKS = "。、！？!?「」『』…"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: ""
        val container = AppContainer.from(this)

        setContent {
            DsmTheme {
                if (text.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No text received")
                    }
                    return@DsmTheme
                }
                SelectionPopup(text, container) { finish() }
            }
        }
    }

    @Composable
    private fun SelectionPopup(text: String, container: AppContainer, onClose: () -> Unit) {
        var matches by remember { mutableStateOf<List<ScannedWord>?>(null) }
        var selected by remember { mutableStateOf<String?>(null) }
        var line by remember { mutableStateOf<io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity?>(null) }

        androidx.compose.runtime.LaunchedEffect(text) {
            // Ties the selection to the text log so the card gets a full sentence and its audio.
            line = container.textLog.lineForSharedText(text.trim().take(400))
            val trimmed = text.trim().take(400)
            // A short selection is the word; shared text is usually a sentence, even a short one.
            if (trimmed.length <= 12 && trimmed.none { it in SENTENCE_MARKS }) {
                selected = trimmed
                matches = emptyList()
            } else {
                val scanned = container.lookup.scan(trimmed)
                matches = scanned
                selected = scanned.firstOrNull()?.text
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Droid Sentence Miner", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (line?.text ?: text.trim()).take(160),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                    val currentMatches = matches ?: emptyList()
                    if (currentMatches.size > 1) {
                        Text("Words found", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            currentMatches.take(8).forEach { match ->
                                OutlinedButton(onClick = { selected = match.text }) { Text(match.text) }
                            }
                        }
                    }
                    if (matches == null) {
                        Text("Scanning…", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { selected = selected ?: text.trim().take(12) }) {
                        Text("Look up")
                    }
                    OutlinedButton(onClick = onClose) { Text("Dismiss") }
                }
            }
        }

        selected?.let { word ->
            val sentenceLine = line
            WordLookupDialog(
                word = word,
                sentence = sentenceLine?.text ?: text,
                image = null,
                onDismiss = { selected = null },
                onMine = sentenceLine?.let { l -> { details, edits -> container.miner.mineLine(l, word, details, edits = edits) } },
                loadAudio = sentenceLine?.let { l -> { container.miner.lineAudio(l) } },
            )
        }
    }
}
