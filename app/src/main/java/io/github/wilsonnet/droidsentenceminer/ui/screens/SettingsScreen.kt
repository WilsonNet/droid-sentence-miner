package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.BuildConfig
import io.github.wilsonnet.droidsentenceminer.audio.AudioCaptureManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpen: (String) -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    var twoPass by remember { mutableStateOf(true) }
    var dropNoise by remember { mutableStateOf(true) }
    var audioEnabled by remember { mutableStateOf(false) }
    var hasAudioPermission by remember { mutableStateOf(AudioCaptureManager.hasPermission(context)) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            audioEnabled = true
            scope.launch { container.prefs.setAudioEnabled(true) }
        } else {
            audioEnabled = false
            scope.launch { container.prefs.setAudioEnabled(false) }
        }
    }

    val autoInterval by container.prefs.autoOcrIntervalMs.collectAsState(initial = 400)
    val liveWindow by container.prefs.liveTextWindow.collectAsState(initial = true)
    val screenDictionary by container.prefs.screenDictionary.collectAsState(initial = true)
    val pauseOnLookup by container.prefs.pauseOnLookup.collectAsState(initial = true)
    val engine by container.prefs.ocrEngine.collectAsState(initial = io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.ENGINE_MEIKI)
    var meikiInstalled by remember { mutableStateOf(io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.installed(context)) }
    var meikiProgress by remember { mutableStateOf<Int?>(null) }
    var meikiError by remember { mutableStateOf<String?>(null) }
    val audioTrim by container.prefs.audioTrim.collectAsState(initial = false)
    val wordAudio by container.prefs.wordAudio.collectAsState(initial = true)
    val micFallback by container.prefs.audioMicFallback.collectAsState(initial = false)
    val normalize by container.prefs.audioNormalize.collectAsState(initial = true)
    val noiseSuppression by container.prefs.audioNoiseSuppression.collectAsState(initial = true)
    val micCompressor by container.prefs.audioMicCompressor.collectAsState(initial = true)
    val micEq by container.prefs.audioMicEq.collectAsState(initial = true)
    val micEqProfile by container.prefs.audioMicEqProfile.collectAsState(initial = null)
    val firstDict by container.prefs.glossaryFirstDictionary.collectAsState(initial = false)
    val wordAudioUrl by container.prefs.wordAudioCustomUrl.collectAsState(initial = "")
    var wordAudioUrlDraft by remember(wordAudioUrl) { mutableStateOf(wordAudioUrl) }
    val bufferSeconds by container.prefs.audioBufferSeconds.collectAsState(initial = 300)
    val clipBefore by container.prefs.clipBeforeMs.collectAsState(initial = 500)
    val clipAfter by container.prefs.clipAfterMs.collectAsState(initial = 500)

    LaunchedEffect(Unit) {
        twoPass = container.prefs.ocrTwoPass.first()
        dropNoise = container.prefs.ocrDropNoise.first()
        audioEnabled = container.prefs.audioEnabled.first()
        hasAudioPermission = AudioCaptureManager.hasPermission(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        val dictionaries by remember { container.db.dictionaryDao().observeAll() }.collectAsState(initial = emptyList())
        val dictUpdates by container.dictUpdates.collectAsState()
        val ankiDeck by container.prefs.ankiDeck.collectAsState(initial = "")
        LaunchedEffect(dictionaries.size) {
            if (dictionaries.isNotEmpty() && dictUpdates.isEmpty()) {
                runCatching { container.refreshDictUpdates() }
            }
        }
        val dictSubtitle = when {
            dictionaries.isEmpty() -> "None installed yet: install Jitendex here"
            dictUpdates.isNotEmpty() -> "${dictionaries.size} installed · ${dictUpdates.size} update${if (dictUpdates.size > 1) "s" else ""} available"
            else -> "${dictionaries.size} installed"
        }
        SettingsLink(
            Icons.Default.Book,
            "Dictionaries",
            dictSubtitle,
        ) { onOpen(io.github.wilsonnet.droidsentenceminer.PAGE_DICTIONARIES) }
        SettingsLink(
            Icons.Default.Style,
            "Anki",
            "Backend, deck, note type and fields" + if (ankiDeck.isNotBlank()) " · deck: $ankiDeck" else "",
        ) { onOpen(io.github.wilsonnet.droidsentenceminer.PAGE_ANKI) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OCR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val meiki = io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.ENGINE_MEIKI
                val mlkit = io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.ENGINE_MLKIT
                Text("Engine", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = engine == meiki,
                        onClick = { scope.launch { container.prefs.setOcrEngine(meiki) } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("meikiocr (recommended)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Trained on Japanese games: much more accurate on stylized and outlined text. " +
                                if (meikiInstalled) "Installed." else "Needs a one-time 31 MB download.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = engine == mlkit,
                        onClick = { scope.launch { container.prefs.setOcrEngine(mlkit) } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Google ML Kit", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Built in, no download. Weaker on game fonts (small kana, dakuten).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (engine == meiki && !meikiInstalled) {
                    val progress = meikiProgress
                    if (progress != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        androidx.compose.material3.FilledTonalButton(onClick = {
                            meikiError = null
                            meikiProgress = 0
                            scope.launch {
                                val error = io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.download(context) { meikiProgress = it }
                                meikiProgress = null
                                meikiError = error
                                meikiInstalled = io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.installed(context)
                            }
                        }) { Text("Download meikiocr models (31 MB)") }
                        Text(
                            "Until then ML Kit is used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    meikiError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                SettingSwitch(
                    title = "Two-pass OCR (ML Kit)",
                    description = "Re-read detected lines at higher resolution and keep the better result. More accurate, a bit slower.",
                    checked = twoPass,
                ) { value ->
                    twoPass = value
                    scope.launch { container.prefs.setOcrTwoPass(value) }
                }
                SettingSwitch(
                    title = "Filter non-Japanese lines",
                    description = "Drop UI noise such as menus or numbers when Japanese text is present.",
                    checked = dropNoise,
                ) { value ->
                    dropNoise = value
                    scope.launch { container.prefs.setOcrDropNoise(value) }
                }
                SettingSwitch(
                    title = "Live text window",
                    description = "Show a small floating feed of new lines over the game while auto OCR runs. Tap a word to look it up, hold a line for the panel.",
                    checked = liveWindow,
                ) { value -> scope.launch { container.prefs.setLiveTextWindow(value) } }
                SettingSwitch(
                    title = "Tap text on screen to look it up",
                    description = "While auto OCR runs, the text it reads is laid invisibly over the real subtitles or dialogue, in any app: tap a character for its word, hold and drag to select.",
                    checked = screenDictionary,
                ) { value -> scope.launch { container.prefs.setScreenDictionary(value) } }
                SettingSwitch(
                    title = "Pause the video while looking up",
                    description = "A tap on screen text pauses playback (YouTube and most players) and resumes it when the definition closes.",
                    checked = pauseOnLookup,
                ) { value -> scope.launch { container.prefs.setPauseOnLookup(value) } }
                SettingSlider(
                    title = "Auto OCR interval",
                    value = "$autoInterval ms",
                    description = "How often auto OCR reads the region. Lower catches short lines sooner; higher saves battery.",
                    position = autoInterval.toFloat(),
                    range = 200f..2000f,
                    steps = 17,
                ) { scope.launch { container.prefs.setAutoOcrIntervalMs(it.toInt()) } }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SettingSwitch(
                    title = "Glossary: first dictionary only",
                    description = "Put only your highest-priority dictionary on the card (Yomitan's {glossary-first}), " +
                        "e.g. a monolingual dictionary ordered first in Settings → Dictionaries.",
                    checked = firstDict,
                ) { value -> scope.launch { container.prefs.setGlossaryFirstDictionary(value) } }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sentence audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SettingSwitch(
                    title = "Capture game audio",
                    description = "Records the voice line playing on screen and attaches it to the card. " +
                        "Requires the screen-capture session and the microphone permission " +
                        "(Android only allows audio capture while a MediaProjection is active).",
                    checked = audioEnabled && hasAudioPermission,
                ) { value ->
                    if (value && !hasAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        audioEnabled = value
                        scope.launch { container.prefs.setAudioEnabled(value) }
                    }
                }
                if (audioEnabled && hasAudioPermission) {
                    SettingSwitch(
                        title = "Microphone fallback",
                        description = "Some apps block audio capture (DroidDeck, Netflix) and only silence gets recorded. " +
                            "When that happens, record the microphone instead: works with the speaker on, " +
                            "picks up room noise, records nothing with headphones.",
                        checked = micFallback,
                    ) { value -> scope.launch { container.prefs.setAudioMicFallback(value) } }
                    if (micFallback) {
                        SettingSwitch(
                            title = "Noise suppression",
                            description = "Android's voice noise filter on the microphone (the one calls use): " +
                                "less fan and room noise, but it can also soften music and effects.",
                            checked = noiseSuppression,
                        ) { value -> scope.launch { container.prefs.setAudioNoiseSuppression(value) } }
                        SettingSwitch(
                            title = "Speaker correction EQ",
                            description = "Undoes the tinny sound of a speaker heard through the microphone: cuts rumble " +
                                "under 120 Hz, then applies the curve measured on this device — fuller low mids, " +
                                "the harsh 4–8 kHz peak tamed.",
                            checked = micEq,
                        ) { value -> scope.launch { container.prefs.setAudioMicEq(value) } }
                        if (micEq) MicEqCalibration(container, micEqProfile)
                        SettingSwitch(
                            title = "Compressor",
                            description = "Evens out the microphone after the EQ: loud peaks are squeezed " +
                                "so quiet, thin dialogue comes out fuller once the clip is normalized.",
                            checked = micCompressor,
                        ) { value -> scope.launch { container.prefs.setAudioMicCompressor(value) } }
                    }
                    SettingSwitch(
                        title = "Normalize volume",
                        description = "Bring every sentence clip to the same loudness, so quiet games, loud videos " +
                            "and microphone recordings sound alike on your cards.",
                        checked = normalize,
                    ) { value -> scope.launch { container.prefs.setAudioNormalize(value) } }
                }
                SettingSwitch(
                    title = "Word audio",
                    description = "Add the word's pronunciation to the card like Yomitan: JapanesePod101, then " +
                        "JapanesePod101 search, then Jisho. Needs internet when mining.",
                    checked = wordAudio,
                ) { value -> scope.launch { container.prefs.setWordAudio(value) } }
                if (wordAudio) {
                    androidx.compose.material3.OutlinedTextField(
                        value = wordAudioUrlDraft,
                        onValueChange = { wordAudioUrlDraft = it },
                        label = { Text("Custom audio URL (optional)") },
                        placeholder = { Text("http://127.0.0.1:5050/?term={term}&reading={reading}") },
                        supportingText = { Text("Tried first, e.g. a local audio server. {term} and {reading} are replaced.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (wordAudioUrlDraft != wordAudioUrl) {
                                androidx.compose.material3.TextButton(onClick = {
                                    scope.launch { container.prefs.setWordAudioCustomUrl(wordAudioUrlDraft) }
                                }) { Text("Save") }
                            }
                        },
                    )
                }
                SettingSlider(
                    title = "Replay buffer",
                    value = "${bufferSeconds / 60} min",
                    description = "How far back a line can still be mined with audio. Uses about 2.9 MB of memory per minute.",
                    position = bufferSeconds.toFloat(),
                    range = 60f..600f,
                    steps = 8,
                ) { scope.launch { container.prefs.setAudioBufferSeconds(it.toInt()) } }
                SettingSlider(
                    title = "Start offset",
                    value = "$clipBefore ms",
                    description = "Audio kept before the line appeared on screen.",
                    position = clipBefore.toFloat(),
                    range = 0f..3000f,
                    steps = 29,
                ) { scope.launch { container.prefs.setClipBeforeMs(it.toInt()) } }
                SettingSlider(
                    title = "End offset",
                    value = "$clipAfter ms",
                    description = "Audio kept after the line disappeared or was replaced.",
                    position = clipAfter.toFloat(),
                    range = 0f..3000f,
                    steps = 29,
                ) { scope.launch { container.prefs.setClipAfterMs(it.toInt()) } }
                SettingSwitch(
                    title = "Trim silence (experimental)",
                    description = "Cut leading and trailing quiet parts from the clip. Works for voice over quiet scenes; " +
                        "with loud music or ambience it can clip speech, so it is off by default.",
                    checked = audioTrim,
                ) { value -> scope.launch { container.prefs.setAudioTrim(value) } }
                if (!AudioCaptureManager.isSupported()) {
                    Text(
                        "Audio capture needs Android 10 or newer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Some apps opt out of audio capture and would only record silence; the microphone fallback above covers them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Droid Sentence Miner ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A native Android sentence miner inspired by GameSentenceMiner: popup OCR, Yomitan dictionaries and Anki mining.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text("Credits", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "• GameSentenceMiner by bpwhelan — OCR pipeline and mining workflow inspiration\n" +
                        "• Yomitan by yomidevs — dictionary format, deinflection rules, popup UX\n" +
                        "• Yomihon by the Yomihon project — Kotlin dictionary parser and Japanese deinflector reference (GPL-3.0)\n" +
                        "• Jiten by Sirush — deinflection rule source\n" +
                        "• ML Kit by Google — on-device Japanese OCR\n" +
                        "• AnkiDroid — AnkiDroid API and FlashCards contract\n" +
                        "• meikiocr by rtr46 — game-trained OCR models (LGPL-3.0), downloaded at runtime\n" +
                        "• meikidroid by rtr46 — Android game OCR overlay reference\n" +
                        "• ONNX Runtime by Microsoft — on-device model inference\n" +
                        "• OwOCR — OCR two-pass concepts used by GSM\n" +
                        "• Kanjium (CC BY-SA 4.0) — pitch accents. The pitch accent notation, verb particle data, " +
                        "phonetics, homonyms and other additions or modifications to EDICT, KANJIDIC or KRADFILE " +
                        "were provided by Uros O. through his free database.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "License: GPL-3.0. Droid Sentence Miner is not affiliated with Anki, AnkiDroid, Yomitan or GameSentenceMiner.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: String,
    description: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(position) { mutableStateOf(position) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
