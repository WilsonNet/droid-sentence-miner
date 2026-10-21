package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.book.BookOcr
import io.github.wilsonnet.droidsentenceminer.book.BookPage
import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.book.TakePagePhoto
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupDialog
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mining from paper: photograph a page, tap the paragraph on the photo, tap a word in its text.
 * The card gets the word's sentence and the paragraph cut out of the photo as its picture.
 */
@Composable
fun BookScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val page by container.bookPage.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun read(uri: Uri) {
        reading = true
        error = null
        scope.launch {
            val bitmap = BookOcr.loadPhoto(context, uri)
            if (bitmap == null) {
                error = "Could not read the photo"
            } else {
                val result = runCatching { container.bookOcr.read(bitmap) }
                result.onSuccess { read ->
                    container.bookPage.value = read
                    selected = if (read.blocks.size == 1) 0 else -1
                    if (read.blocks.isEmpty()) error = "No Japanese text found. Get closer and keep the page flat and lit."
                }.onFailure { error = "OCR failed: ${it.message}" }
            }
            reading = false
        }
    }

    val photoUri = remember {
        val file = File(context.cacheDir, "camera/page.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val camera = rememberLauncherForActivityResult(TakePagePhoto()) { taken ->
        if (taken) read(photoUri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) read(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Book", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Photograph a page of a book or manga, tap a paragraph, then tap a word to look it up and mine it. " +
                "Vertical and horizontal text both work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !reading, onClick = { runCatching { camera.launch(photoUri) }.onFailure { error = "No camera app found" } }) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Take photo")
            }
            OutlinedButton(
                enabled = !reading,
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }
        }
        if (reading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Reading the page…")
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        page?.let { current ->
            if (current.blocks.isNotEmpty()) {
                val block = current.blocks.getOrNull(selected)
                if (block != null) {
                    BlockCard(
                        page = current,
                        index = selected,
                        onPrev = { selected = (selected - 1).coerceAtLeast(0) },
                        onNext = { selected = (selected + 1).coerceAtMost(current.blocks.lastIndex) },
                    )
                } else {
                    Text(
                        "Tap a paragraph on the photo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            PagePhoto(current, selected) { selected = it }
            Text(
                "${current.blocks.size} paragraphs · ${current.elapsedMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The photo with every paragraph outlined; tapping one selects it. */
@Composable
private fun PagePhoto(page: BookPage, selected: Int, onSelect: (Int) -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    val bitmap = page.bitmap
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height),
    ) {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = "Page photo",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(page) {
                    detectTapGestures { tap ->
                        val scale = size.width.toFloat() / bitmap.width
                        val x = tap.x / scale
                        val y = tap.y / scale
                        val slack = 24f / scale
                        val hit = page.blocks.indices.filter { i ->
                            val b = page.blocks[i].box
                            x >= b.left - slack && x <= b.right + slack && y >= b.top - slack && y <= b.bottom + slack
                        }.minByOrNull { i -> page.blocks[i].box.let { it.width.toLong() * it.height } }
                        if (hit != null) onSelect(hit)
                    }
                },
        ) {
            val scale = size.width / bitmap.width
            page.blocks.forEachIndexed { i, block ->
                val topLeft = Offset(block.box.left * scale, block.box.top * scale)
                val boxSize = Size(block.box.width * scale, block.box.height * scale)
                if (i == selected) drawRect(outline.copy(alpha = 0.25f), topLeft, boxSize)
                drawRect(outline.copy(alpha = if (i == selected) 1f else 0.6f), topLeft, boxSize, style = Stroke(width = if (i == selected) 3.dp.toPx() else 1.5.dp.toPx()))
            }
        }
    }
}

/** The selected paragraph as text: dictionary words are underlined, tapping one opens the lookup. */
@Composable
private fun BlockCard(page: BookPage, index: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val block = page.blocks[index]
    var words by remember(block) { mutableStateOf<List<ScannedWord>>(emptyList()) }
    var tapped by remember(block) { mutableStateOf<ScannedWord?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(block) { words = container.lookup.scan(block.text) }

    val highlight = MaterialTheme.colorScheme.primaryContainer
    var editing by remember { mutableStateOf(false) }
    val text = buildAnnotatedString {
        append(block.text)
        tapped?.let { addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold), it.start, it.end) }
    }

    Card {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Paragraph ${index + 1} of ${page.blocks.size}" + if (words.isEmpty()) "" else " · tap a word",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Fix the text")
                }
                IconButton(onClick = onPrev, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous paragraph")
                }
                IconButton(onClick = onNext, enabled = index < page.blocks.lastIndex) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next paragraph")
                }
            }
            Text(
                text = text,
                fontSize = 22.sp,
                lineHeight = 36.sp,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .pointerInput(words) {
                        detectTapGestures { pos ->
                            val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                            words.firstOrNull { offset >= it.start && offset < it.end }?.let { tapped = it }
                        }
                    },
            )
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(block.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Fix the text") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fixed = draft.replace("\n", "").trim()
                    if (fixed.isNotEmpty()) {
                        container.bookPage.value = page.copy(
                            blocks = page.blocks.toMutableList().also { it[index] = block.copy(text = fixed) },
                        )
                    }
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }

    tapped?.let { word ->
        val sentence = remember(word) { PageLayout.sentenceAt(block.text, word.start) }
        val image = remember(block) { BookOcr.cardImage(page, block) }
        WordLookupDialog(
            word = word.text,
            sentence = sentence,
            image = image,
            onDismiss = { tapped = null },
        )
    }
}
