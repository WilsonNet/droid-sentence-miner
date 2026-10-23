package io.github.wilsonnet.droidsentenceminer.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBlock(
    html: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Tap on glossary text: (text from the tapped character on, its surrounding sentence). */
    onTextTap: ((String, String) -> Unit)? = null,
) {
    val tapHandler = androidx.compose.runtime.rememberUpdatedState(onTextTap)
    Box(modifier = modifier.background(if (dark) ComposeColor(0xFF1E1E1E) else ComposeColor.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = onTextTap != null
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    webViewClient = WebViewClient()
                    if (onTextTap != null) {
                        addJavascriptInterface(
                            object {
                                @android.webkit.JavascriptInterface
                                fun tap(text: String, context: String) {
                                    post { tapHandler.value?.invoke(text, context) }
                                }
                            },
                            "dsm",
                        )
                    }
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    val page = if (onTextTap != null) html + TAP_SCRIPT else html
                    view.loadDataWithBaseURL("file:///", page, "text/html", "UTF-8", null)
                }
            },
        )
    }
}

/**
 * Yomitan-style scanning inside a definition: the text from the tapped character to the end of
 * its block (furigana removed), plus the whole block as the sentence.
 */
private const val TAP_SCRIPT = """<script>
document.addEventListener('click', function (e) {
  var r = document.caretRangeFromPoint(e.clientX, e.clientY);
  if (!r || r.startContainer.nodeType !== 3) return;
  var node = r.startContainer, off = r.startOffset;
  if (node.parentElement.closest('rt,rp')) return;
  if (off > 0) {
    var p = document.createRange(); p.setStart(node, off - 1); p.setEnd(node, off);
    var b = p.getBoundingClientRect();
    if (e.clientX >= b.left && e.clientX <= b.right && e.clientY >= b.top && e.clientY <= b.bottom) off--;
  }
  if (off >= node.textContent.length) return;
  var block = node.parentElement.closest('li,p,td,div') || document.body;
  var range = document.createRange(); range.setStart(node, off); range.setEnd(block, block.childNodes.length);
  var after = range.cloneContents(); after.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
  var whole = block.cloneNode(true); whole.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
  dsm.tap(after.textContent.substring(0, 24), whole.textContent.trim());
});
</script>"""

/** A lookup in the popup's history; recursive lookups from the definition push new ones. */
private data class LookupEntry(val word: String, val sentence: String)

/** Recursive lookup: tapping Japanese in a definition pushes its first word. */
@Composable
private fun rememberLookupStack(word: String, sentence: String): androidx.compose.runtime.snapshots.SnapshotStateList<LookupEntry> =
    remember(word, sentence) { androidx.compose.runtime.mutableStateListOf(LookupEntry(word, sentence)) }

@Composable
private fun recursiveTap(stack: MutableList<LookupEntry>): (String, String) -> Unit {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    return { text, sentence ->
        if (text.isNotEmpty() && TermLookup.isLookupable(text[0])) {
            scope.launch {
                val first = container.lookup.scan(text).firstOrNull { it.start == 0 } ?: return@launch
                if (first.text != stack.last().word) stack += LookupEntry(first.text, sentence.take(300))
            }
        }
    }
}

/** Lookup + mining state shared by [WordLookupDialog] and [WordLookupPopup]. */
private class WordLookupState {
    var loading by mutableStateOf(true)
    var details by mutableStateOf<TermLookup.LookupDetails?>(null)
    var html by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var mining by mutableStateOf(false)

    /** A card was just added from this popup (the button turns green). */
    var added by mutableStateOf(false)

    /** The word already has an Anki note (Yomitan's duplicate tag); null until checked. */
    var inAnki by mutableStateOf<Boolean?>(null)
}

@Composable
private fun rememberWordLookup(word: String, dark: Boolean): WordLookupState {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val state = remember(word) { WordLookupState() }
    LaunchedEffect(word) {
        val result = container.lookup.lookup(word)
        state.details = result
        state.html = container.miner.buildPopupHtml(word, result, dark)
        state.loading = false
        if (result.terms.isNotEmpty()) state.inAnki = container.miner.isInAnki(word, result)
    }
    return state
}

@Composable
private fun mineAction(
    state: WordLookupState,
    word: String,
    sentence: String,
    image: Bitmap?,
    onMine: (suspend (TermLookup.LookupDetails?) -> AnkiResult<String>)?,
): () -> Unit {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    return {
        state.mining = true
        state.status = null
        // App scope, not the popup's: closing the popup right after tapping must not drop the card.
        container.appScope.launch {
            val result = onMine?.invoke(state.details) ?: container.miner.mine(word, sentence, image, state.details)
            withContext(Dispatchers.Main) {
                if (result is AnkiResult.Success) {
                    state.inAnki = true
                    state.added = true
                }
                state.status = when (result) {
                    is AnkiResult.Success -> null
                    is AnkiResult.Error -> "Anki: ${result.message}"
                    AnkiResult.NotAvailable -> "Anki backend not available"
                }
                state.mining = false
                // A toast too: the popup may be closed by now, and it is visible over the game either way.
                val toast = when (result) {
                    is AnkiResult.Success -> "✓ Added 「$word」 to Anki"
                    is AnkiResult.Error -> "Anki card not added: ${result.message}"
                    AnkiResult.NotAvailable -> "Anki card not added: backend not available"
                }
                Toast.makeText(context.applicationContext, toast, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun LookupHeader(
    state: WordLookupState,
    word: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) = Row(modifier, verticalAlignment = Alignment.Top) {
    if (onBack != null) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to previous word")
        }
    }
    Column {
        Text(state.details?.baseForm ?: word, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        state.details?.reading?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.details?.deinflection?.takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LookupBody(
    state: WordLookupState,
    dark: Boolean,
    maxHeight: androidx.compose.ui.unit.Dp,
    onTextTap: ((String, String) -> Unit)? = null,
) {
    if (state.loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking up…")
        }
        return
    }
    val currentHtml = state.html
    if (currentHtml.isNullOrBlank() || state.details?.terms.isNullOrEmpty()) {
        Text("No dictionary entry found.")
    } else {
        WebViewBlock(
            html = currentHtml,
            dark = dark,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            onTextTap = onTextTap,
        )
    }
}

private val ADDED_GREEN = androidx.compose.ui.graphics.Color(0xFF2E7D32)

@Composable
private fun MineButton(state: WordLookupState, onClick: () -> Unit) {
    // Like Yomitan, a word that already has a note shows it instead of offering a duplicate.
    val inAnki = state.inAnki == true
    val addedColors = ButtonDefaults.filledTonalButtonColors(
        disabledContainerColor = ADDED_GREEN,
        disabledContentColor = androidx.compose.ui.graphics.Color.White,
    )
    FilledTonalButton(
        enabled = !inAnki && !state.mining && state.details?.terms?.isNotEmpty() == true,
        onClick = onClick,
        colors = if (state.added) addedColors else ButtonDefaults.filledTonalButtonColors(),
    ) {
        when {
            state.mining -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(if (inAnki) Icons.Default.Check else Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                state.mining -> "Adding…"
                state.added -> "Added"
                inAnki -> "In Anki"
                else -> "Anki"
            },
        )
    }
}

/**
 * Word lookup dialog with definitions and one-tap Anki mining.
 * Used from the OCR test screen and the text-selection popup.
 */
@Composable
fun WordLookupDialog(
    word: String,
    sentence: String,
    image: Bitmap?,
    onDismiss: () -> Unit,
    /** Overrides the default mining (e.g. to attach a text-log line's audio and screenshot). */
    onMine: (suspend (TermLookup.LookupDetails?) -> AnkiResult<String>)? = null,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    // A word reached from a definition gets that definition's sentence, and no line audio/screenshot.
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 })
    val onTap = recursiveTap(stack)
    if (stack.size > 1) androidx.activity.compose.BackHandler { stack.removeAt(stack.lastIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LookupHeader(state, entry.word, onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null) },
        text = {
            // No verticalScroll here: the WebView scrolls itself, and an outer scroll fought it for drags.
            Column(Modifier.heightIn(max = 420.dp)) {
                LookupBody(state, dark, maxHeight = 360.dp, onTextTap = onTap)
                state.status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { MineButton(state, mine) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * The lookup as a popup attached to the word: below it when there is room, above otherwise,
 * clamped to the window. No animation, no scrim, the text under it stays tappable, so tapping
 * another word just moves the popup. [anchor] is the word's bounds in window pixels.
 */
@Composable
fun WordLookupPopup(
    word: String,
    sentence: String,
    image: Bitmap?,
    anchor: androidx.compose.ui.geometry.Rect,
    onDismiss: () -> Unit,
    onMine: (suspend (TermLookup.LookupDetails?) -> AnkiResult<String>)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val gap = with(density) { 8.dp.roundToPx() }
    val plan = rememberPopupPlan(
        anchor,
        with(density) { config.screenWidthDp.dp.toPx() },
        with(density) { config.screenHeightDp.dp.toPx() },
    )
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember(anchor, gap, plan) { AnchoredPopupPosition(anchor, gap, plan.side) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        WordLookupCard(
            word = word,
            sentence = sentence,
            image = image,
            onDismiss = onDismiss,
            onMine = onMine,
            maxHeight = with(density) { plan.maxHeight.toDp() },
            handleBack = true,
        )
    }
}

/** The popup's width: the screen minus margins, at most 420 dp (600 dp in landscape, where height is short). */
@Composable
fun popupWidth(): androidx.compose.ui.unit.Dp {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return popupWidth(config.screenWidthDp.dp, config.screenHeightDp.dp)
}

/** [popupWidth] for a screen of the given size (overlay windows: their Configuration can lag rotation). */
fun popupWidth(screenWidth: androidx.compose.ui.unit.Dp, screenHeight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val cap = if (screenWidth > screenHeight) 600.dp else 420.dp
    return minOf(screenWidth - 24.dp, cap)
}

/**
 * Popper-style placement next to [anchor] (window px) on a [screenWidth]×[screenHeight] px screen:
 * the side with room for a definition, and the popup's height limited to that room.
 */
@Composable
fun rememberPopupPlan(
    anchor: androidx.compose.ui.geometry.Rect,
    screenWidth: Float,
    screenHeight: Float,
    popupWidth: androidx.compose.ui.unit.Dp = popupWidth(),
): PopupPlacement.Plan {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val width = with(density) { popupWidth.toPx() }
    val preferred = with(density) { 260.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    return remember(anchor, screenWidth, screenHeight, width) {
        PopupPlacement.plan(anchor.left, anchor.top, anchor.right, anchor.bottom, screenWidth, screenHeight, width, preferred, gap)
    }
}

/**
 * The popup's content: headword, Anki button, definition (tap Japanese in it to look that up,
 * ← to go back). Hosted by [WordLookupPopup] in the app and by an overlay window over other apps.
 * [handleBack] needs an OnBackPressedDispatcher, so it is off in overlay windows.
 */
@Composable
fun WordLookupCard(
    word: String,
    sentence: String,
    image: Bitmap?,
    onDismiss: () -> Unit,
    onMine: (suspend (TermLookup.LookupDetails?) -> AnkiResult<String>)?,
    maxHeight: androidx.compose.ui.unit.Dp,
    handleBack: Boolean,
    width: androidx.compose.ui.unit.Dp = popupWidth(),
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 })
    val onTap = recursiveTap(stack)

    if (handleBack) {
        androidx.activity.compose.BackHandler {
            if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
        }
    }
    androidx.compose.material3.Surface(
        modifier = Modifier
            .width(width)
            .heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                LookupHeader(
                    state,
                    entry.word,
                    Modifier.weight(1f).padding(top = 6.dp),
                    onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null,
                )
                MineButton(state, mine)
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            state.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(4.dp))
            // The rest of the card, whatever the header took: a fixed guess for the header clipped the
            // bottom of the definition when the header was taller (deinflection line, error, back).
            Box(Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                LookupBody(state, dark, maxHeight = maxHeight, onTextTap = onTap)
            }
        }
    }
}

/** Places the measured popup on [side] of [anchor] (window px), shifted to stay on screen. */
class AnchoredPopupPosition(
    private val anchor: androidx.compose.ui.geometry.Rect,
    private val gap: Int,
    private val side: PopupPlacement.Side,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val (x, y) = PopupPlacement.position(
            side,
            anchor.left,
            anchor.top,
            anchor.right,
            anchor.bottom,
            windowSize.width.toFloat(),
            windowSize.height.toFloat(),
            popupContentSize.width.toFloat(),
            popupContentSize.height.toFloat(),
            gap.toFloat(),
        )
        return androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
    }
}
