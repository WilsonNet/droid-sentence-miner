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
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.BuildConfig
import io.github.wilsonnet.droidsentenceminer.audio.AudioCaptureManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
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
    val audioTrim by container.prefs.audioTrim.collectAsState(initial = false)
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

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OCR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SettingSwitch(
                    title = "Two-pass OCR",
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
                    description = "Show a small floating feed of new lines over the game while auto OCR runs. Tap a line to mine it.",
                    checked = liveWindow,
                ) { value -> scope.launch { container.prefs.setLiveTextWindow(value) } }
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

        Card {
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
                    "Some apps opt out of audio capture and will stay silent (a lock icon appears on their media notification).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
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
                        "• OwOCR / MeikiOCR — OCR two-pass concepts used by GSM",
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
