package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import kotlinx.coroutines.launch

/** What each language gets, one line, for the picker. Japanese is the project's main focus. */
private fun blurb(language: LanguageModule): String = when (language.code) {
    "ja" -> "Main focus. Game-trained OCR, pitch accent, word audio, vertical book pages."
    "en" -> "Wiktionary dictionaries, inflections and phrasal verbs, PaddleOCR (optional ${language.pack?.sizeMb?.toInt()} MB download)."
    else -> ""
}

/**
 * First run: pick the study language before anything else. A screen, not a dialog: nothing
 * works without the answer, and the choice is the whole task. Japanese is preselected.
 */
@Composable
fun LanguageSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(Languages.DEFAULT_CODE) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text("What are you learning?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Droid Sentence Miner reads text on your screen, looks words up and makes Anki cards. " +
                        "Only the language you pick downloads dictionaries and models.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                LanguageChoices(selected = selected, onSelect = { selected = it })
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        scope.launch {
                            container.chooseLanguage(selected)
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Continue in ${Languages.byCode(selected).displayName}") }
                Spacer(Modifier.height(12.dp))
                Text(
                    "You can switch any time in Settings → Language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The language list: one selectable row per language, radio on the left (Material list pattern). */
@Composable
private fun LanguageChoices(selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Languages.ALL.forEach { language ->
            val isSelected = language.code == selected
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(language.code) }),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(language.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (language.nativeName != language.displayName) {
                                Spacer(Modifier.width(8.dp))
                                Text(language.nativeName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(blurb(language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Settings → Language: the study language and its downloadable pack. */
@Composable
fun LanguageSettingsCard() {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val code by container.languageChoice.collectAsState()
    val language = Languages.byCode(code)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Language", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Lookups, OCR filtering and the Dictionaries page follow the language you study. " +
                    "Dictionaries of other languages stay installed, unused.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LanguageChoices(selected = language.code, onSelect = { picked ->
                scope.launch { container.prefs.setLanguage(picked) }
            })
            language.pack?.let { LanguagePackRow(it) }
        }
    }
}

/** A pack's states: not installed (download + size), downloading (progress), installed (remove), failed (retry). */
@Composable
fun LanguagePackRow(pack: LanguagePack) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val install by container.packInstall.collectAsState()
    val state = install?.takeIf { it.language == pack.language }
    val installedVersion = state?.version?.takeIf { state.progress == null } ?: LanguagePacks.installedVersion(context, pack.language)
    val installed = installedVersion >= pack.version

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pack.name, style = MaterialTheme.typography.bodyLarge)
        Text(pack.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val progress = state?.progress
        when {
            progress != null -> {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… $progress %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Installed · used for ${Languages.byCode(pack.language).displayName} OCR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { container.removeLanguagePack(pack.language) }) { Text("Remove") }
            }
            else -> {
                FilledTonalButton(onClick = { container.installLanguagePack(pack) }) {
                    Text(if (state?.error != null) "Retry download (${pack.sizeMb.toInt()} MB)" else "Download (${pack.sizeMb.toInt()} MB)")
                }
                Text(
                    state?.error?.let { "Download failed: $it. Check the connection and retry." }
                        ?: "Until then the built-in ML Kit reader is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
