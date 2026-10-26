package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import io.github.wilsonnet.droidsentenceminer.capture.ScreenshotAccessibilityService
import androidx.lifecycle.LifecycleEventObserver
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.overlay.OverlayService
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasScreenshots by remember { mutableStateOf(ScreenshotAccessibilityService.isEnabled(context)) }
    var hasNotifications by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val running by OverlayService.observeRunning().collectAsState()
    val dictionaries by remember { container.db.dictionaryDao().observeAll() }
        .collectAsState(initial = emptyList())
    val dictUpdates by container.dictUpdates.collectAsState()
    val recentCards by remember { container.db.minedCardDao().observeRecent() }
        .collectAsState(initial = emptyList())

    LaunchedEffect(dictionaries.size) {
        if (dictionaries.isNotEmpty() && dictUpdates.isEmpty()) {
            runCatching { container.refreshDictUpdates() }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasScreenshots = ScreenshotAccessibilityService.isEnabled(context)
                hasNotifications = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasNotifications = granted }

    var pitchStatus by remember { mutableStateOf<String?>(null) }
    var dictUpdateStatus by remember { mutableStateOf<String?>(null) }
    var dictUpdating by remember { mutableStateOf(false) }

    // ---- OCR test state
    var testing by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf<String?>(null) }
    var testMatches by remember { mutableStateOf<List<ScannedWord>>(emptyList()) }
    var testImage by remember { mutableStateOf<Bitmap?>(null) }
    var testInfo by remember { mutableStateOf<String?>(null) }
    var lookupWord by remember { mutableStateOf<String?>(null) }

    fun runTest(bitmap: Bitmap, label: String) {
        testing = true
        testText = null
        testMatches = emptyList()
        testInfo = null
        scope.launch {
            val result = try {
                val twoPass = container.prefs.ocrTwoPass.first()
                container.ocrEngine(twoPass).recognize(bitmap)
            } catch (e: Exception) {
                testing = false
                testInfo = "OCR failed: ${e.message}"
                return@launch
            }
            val text = result.text.trim()
            val matches = if (text.isBlank()) emptyList() else container.lookup.scan(text)
            testImage = bitmap
            testText = text
            testMatches = matches
            testInfo = "$label · ${result.elapsedMs} ms · ${matches.size} words found"
            testing = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, uri) }
                if (bitmap != null) runTest(bitmap, "Picked image")
                else testInfo = "Could not read the selected image"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Droid Sentence Miner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "OCR Japanese text from any app, look words up with Yomitan dictionaries and mine them straight to Anki.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Floating overlay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusLine("Overlay permission", hasOverlay)
                StatusLine("Notifications", hasNotifications)
                if (ScreenshotAccessibilityService.supported) {
                    StatusLine("Quick lookup without screen-sharing prompt", hasScreenshots)
                    if (!hasScreenshots) {
                        Text(
                            "Accessibility → Installed apps → ${context.getString(io.github.wilsonnet.droidsentenceminer.R.string.accessibility_label)}. " +
                                "Only takes a screenshot when you box a word; auto OCR still asks for screen sharing (it records the audio too). " +
                                "If it's greyed out: App info → ⋮ → Allow restricted settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { ScreenshotAccessibilityService.openSettings(context) }) {
                            Text("Open accessibility settings")
                        }
                    }
                }
                StatusLine("Dictionaries installed", dictionaries.isNotEmpty(), "${dictionaries.size}")
                dictUpdates.values.firstOrNull()?.let { update ->
                    val status = dictUpdateStatus
                    if (status != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (dictUpdating) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        OutlinedButton(
                            enabled = !dictUpdating,
                            onClick = {
                                dictUpdating = true
                                dictUpdateStatus = "Downloading ${update.latestTitle}…"
                                scope.launch {
                                    try {
                                        val file = java.io.File(context.cacheDir, "update-${update.dictId}.zip")
                                        container.dictDownloader.download(update.downloadUrl, file) { downloaded, total ->
                                            dictUpdateStatus = if (total != null && total > 0) {
                                                "Downloading ${update.latestTitle}… ${downloaded * 100 / total}%"
                                            } else {
                                                "Downloading ${update.latestTitle}… ${downloaded / (1024 * 1024)} MB"
                                            }
                                        }
                                        dictUpdateStatus = "Updating to ${update.latestTitle}…"
                                        withContext(Dispatchers.IO) {
                                            container.importer.import(file) { p ->
                                                dictUpdateStatus = "Importing ${p.title ?: update.latestTitle}… ${p.current}"
                                            }
                                        }
                                        file.delete()
                                        runCatching { container.refreshDictUpdates() }
                                        dictUpdateStatus = null
                                    } catch (e: Exception) {
                                        dictUpdateStatus = "Update failed: ${e.message}"
                                    } finally {
                                        dictUpdating = false
                                    }
                                }
                            },
                        ) {
                            Text("Update to ${update.latestTitle}")
                        }
                    }
                }
                val hasPitch = dictionaries.any {
                    it.title == io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries.KANJIUM_TITLE ||
                        it.title.contains("pitch", ignoreCase = true) || it.title.contains("NHK")
                }
                StatusLine("Pitch accent dictionary", hasPitch)
                if (!hasPitch) {
                    val status = pitchStatus
                    if (status != null) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = {
                            val dict = io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries.ALL
                                .first { it.id == io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries.PITCH_ID }
                            pitchStatus = "Downloading pitch accents…"
                            scope.launch {
                                pitchStatus = try {
                                    val file = java.io.File(context.cacheDir, "recommended-${dict.id}")
                                    container.dictDownloader.download(dict.url, file) { _, _ -> }
                                    pitchStatus = "Importing pitch accents…"
                                    withContext(Dispatchers.IO) { container.importer.importRecommended(dict, file) }
                                    file.delete()
                                    null
                                } catch (e: Exception) {
                                    "Pitch accent install failed: ${e.message}"
                                }
                            }
                        }) { Text("Install pitch accents (3 MB, for the card pitch graph)") }
                    }
                }
                StatusLine("Overlay running", running)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasOverlay) {
                        Button(onClick = { openOverlaySettings(context) }) { Text("Grant overlay permission") }
                    } else if (!running) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !hasNotifications) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            OverlayService.start(context)
                        }) { Text("Start floating bubble") }
                    } else {
                        Button(onClick = { OverlayService.scanRegion(context) }) { Text("Scan now") }
                        OutlinedButton(onClick = { OverlayService.stop(context) }) { Text("Stop") }
                    }
                }
                if (!hasOverlay) {
                    Text(
                        "Tap the bubble to select a screen region, long-press it for the menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Test OCR & lookup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !testing,
                        onClick = {
                            scope.launch {
                                val bitmap = withContext(Dispatchers.IO) { loadSampleImage(context) }
                                if (bitmap != null) runTest(bitmap, "Built-in sample")
                                else testInfo = "Sample image missing from assets"
                            }
                        },
                    ) { Text("Run built-in sample") }
                    FilledTonalButton(
                        enabled = !testing,
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ) { Text("Pick image") }
                }
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Running OCR…")
                    }
                }
                testInfo?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                testImage?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    )
                }
                testText?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    if (testMatches.isNotEmpty()) {
                        Text("Matched words", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            testMatches.take(10).forEach { match ->
                                OutlinedButton(onClick = { lookupWord = match.text }) { Text(match.text) }
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent mined cards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (recentCards.isEmpty()) {
                    Text(
                        "Nothing yet. Scan some Japanese and tap Add to Anki.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                    LazyColumn(
                        Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(recentCards) { card ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(card.word, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (card.addedToAnki) "in Anki" else "not in Anki",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (card.addedToAnki) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        formatter.format(Date(card.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    card.sentence.replace("\n", " ").take(90),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Getting started", fontWeight = FontWeight.SemiBold)
                Text("1. Import a Yomitan dictionary (Jitendex or JMdict) in Settings → Dictionaries", style = MaterialTheme.typography.bodySmall)
                Text("2. Configure Anki in Settings → Anki", style = MaterialTheme.typography.bodySmall)
                Text("3. Start the floating bubble and tap it over Japanese text", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    lookupWord?.let { word ->
        WordLookupDialog(
            word = word,
            sentence = testText.orEmpty(),
            image = testImage,
            onDismiss = { lookupWord = null },
        )
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (ok) "●" else "○",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        detail?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun loadSampleImage(context: Context): Bitmap? {
    return runCatching {
        context.assets.open("sample_ja.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
