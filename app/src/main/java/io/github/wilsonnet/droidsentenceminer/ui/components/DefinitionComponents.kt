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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import io.github.wilsonnet.droidsentenceminer.anki.CardEdits
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
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
    /** Per-entry add buttons (`.dsm-add[data-index]`, see [CardMiner.buildPopupHtml]): tap → index. */
    onEntryMine: ((Int) -> Unit)? = null,
    /** Per-entry edit buttons (`.dsm-edit[data-index]`): tap → index, opens the card editor. */
    onEntryEdit: ((Int) -> Unit)? = null,
    /** Button state per entry index: new, mining, added, in-anki, failed. */
    entryStates: Map<Int, String> = emptyMap(),
    /** Tap on a dictionary header (`.dsm-pick`): (entry index, dictionary id) to put on that entry's card. */
    onCardPick: ((Int, Long) -> Unit)? = null,
    /** The dictionary picked for each entry's card; entries without one keep the setting's choice. */
    cardPicks: Map<Int, Long> = emptyMap(),
) {
    val tapHandler = androidx.compose.runtime.rememberUpdatedState(onTextTap)
    val mineHandler = androidx.compose.runtime.rememberUpdatedState(onEntryMine)
    val editHandler = androidx.compose.runtime.rememberUpdatedState(onEntryEdit)
    val pickHandler = androidx.compose.runtime.rememberUpdatedState(onCardPick)
    val scripted = onTextTap != null || onEntryMine != null || onCardPick != null
    Box(modifier = modifier.background(if (dark) ComposeColor(0xFF1E1E1E) else ComposeColor.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                DefinitionWebView(context).apply {
                    settings.javaScriptEnabled = scripted
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loaded = true
                            applyStates()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = true

                        // Glossary images, read from the dictionary zip on the WebView's IO thread.
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                            io.github.wilsonnet.droidsentenceminer.dict.DictMedia.intercept(view.context.applicationContext, request)
                    }
                    if (scripted) {
                        addJavascriptInterface(
                            object {
                                @android.webkit.JavascriptInterface
                                fun tap(text: String, context: String) {
                                    post { tapHandler.value?.invoke(text, context) }
                                }

                                @android.webkit.JavascriptInterface
                                fun mine(index: Int) {
                                    post { mineHandler.value?.invoke(index) }
                                }

                                @android.webkit.JavascriptInterface
                                fun edit(index: Int) {
                                    post { editHandler.value?.invoke(index) }
                                }

                                @android.webkit.JavascriptInterface
                                fun pick(index: Int, dictId: String) {
                                    val id = dictId.toLongOrNull() ?: return
                                    post { pickHandler.value?.invoke(index, id) }
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
                    view.loaded = false
                    val page = html + (if (onTextTap != null) TAP_SCRIPT else "") + (if (onEntryMine != null) ENTRY_SCRIPT else "") +
                        (if (onCardPick != null) PICK_SCRIPT else "")
                    view.loadDataWithBaseURL(io.github.wilsonnet.droidsentenceminer.dict.DictMedia.PAGE_BASE_URL, page, "text/html", "UTF-8", null)
                }
                view.states = entryStates.entries.joinToString(",", "{", "}") { (i, st) -> "\"$i\":\"$st\"" }
                view.picks = cardPicks.entries.joinToString(",", "{", "}") { (i, id) -> "\"$i\":\"$id\"" }
                view.applyStates()
            },
        )
    }
}

/** Remembers the entry button states until the page has loaded, then pushes them into it. */
private class DefinitionWebView(context: android.content.Context) : WebView(context) {
    var loaded = false
    var states = "{}"
    var picks = "{}"

    fun applyStates() {
        if (loaded) evaluateJavascript("window.dsmSetStates && dsmSetStates($states); window.dsmSetPicks && dsmSetPicks($picks)", null)
    }
}

/**
 * Dictionary headers as card pickers: a tap asks for that dictionary on the entry's card, and
 * [dsmSetPicks] marks the picked one (or the setting's choice, `data-default`, when none is).
 * Capture phase, so the header's title is not looked up.
 */
private const val PICK_SCRIPT = """<script>
function dsmSetPicks(picks) {
  document.querySelectorAll('.dsm-pick').forEach(function (h) {
    var p = picks[h.dataset.entry];
    h.dataset.card = p === undefined ? h.dataset.default : (p === h.dataset.dict ? '1' : '0');
  });
}
document.addEventListener('click', function (e) {
  var h = e.target.closest && e.target.closest('.dsm-pick');
  if (!h) return;
  e.stopPropagation(); e.preventDefault();
  dsm.pick(parseInt(h.dataset.entry, 10), h.dataset.dict);
}, true);
</script>"""

/** Yomitan's per-entry add button (+ edit): capture phase, so the tap never reaches the lookup scanner. */
private const val ENTRY_SCRIPT = """<script>
var dsmLabels = {'new': '+ Anki', 'mining': 'Adding…', 'added': '✓ Added', 'in-anki': '✓ In Anki', 'failed': 'Retry'};
function dsmSetStates(states) {
  document.querySelectorAll('.dsm-add').forEach(function (b) {
    var st = states[b.dataset.index] || 'new';
    b.dataset.state = st;
    b.disabled = !(st === 'new' || st === 'failed');
    b.textContent = dsmLabels[st] || dsmLabels['new'];
  });
}
document.addEventListener('click', function (e) {
  var b = e.target.closest && e.target.closest('.dsm-add, .dsm-edit');
  if (!b) return;
  e.stopPropagation(); e.preventDefault();
  if (b.disabled) return;
  var i = parseInt(b.dataset.index, 10);
  if (b.classList.contains('dsm-edit')) dsm.edit(i); else dsm.mine(i);
}, true);
</script>"""

/**
 * Yomitan-style scanning inside a definition: the text from the tapped character to the end of
 * its block (furigana removed), plus the whole block as the sentence. Tapping an internal query
 * link (`a[href^="?query="]`, e.g. Jitendex See-also / Antonym links) looks up that query directly.
 */
private const val TAP_SCRIPT = """<script>
document.addEventListener('click', function (e) {
  var a = e.target.closest && e.target.closest('a[href]');
  if (a) {
    var href = a.getAttribute('href') || '';
    if (href.charAt(0) === '?') {
      e.preventDefault();
      e.stopPropagation();
      var m = /[?&]query=([^&]+)/.exec(href);
      if (m) {
        var q = decodeURIComponent(m[1].replace(/\+/g, ' '));
        var blk = a.closest('li,p,td,div') || document.body;
        var w = blk.cloneNode(true); w.querySelectorAll('rt,rp').forEach(function (n) { n.remove(); });
        dsm.tap(q, w.textContent.trim());
      }
      return;
    }
  }
  // A tap on a glossary image (or a collapsed image's [Image] toggle) is not a lookup.
  if (e.target.closest && e.target.closest('.gloss-image-link, .gloss-image-summary')) return;
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

    /** Per entry of [TermLookup.LookupDetails.entries]; missing until the duplicate check ran. */
    val entryStates = androidx.compose.runtime.mutableStateMapOf<Int, EntryState>()

    /** Per entry: the dictionary tapped in the popup to go on its card instead of the setting's choice. */
    val cardPicks = androidx.compose.runtime.mutableStateMapOf<Int, Long>()

    /** Tapping the picked dictionary again takes the pick back. */
    fun pick(entry: Int, dictId: Long) {
        if (cardPicks[entry] == dictId) cardPicks.remove(entry) else cardPicks[entry] = dictId
    }

    /** What entry [index]'s card is built from: its headword, narrowed to the picked dictionary. */
    fun cardDetails(index: Int): TermLookup.LookupDetails? = details?.forEntry(index)?.withDictionary(cardPicks[index])

    // The header button stands for the first entry.
    val mining: Boolean get() = entryStates[0] == EntryState.MINING
    val added: Boolean get() = entryStates[0] == EntryState.ADDED

    /** The word already has an Anki note (Yomitan's duplicate tag); null until checked. */
    val inAnki: Boolean?
        get() = when (entryStates[0]) {
            null -> null
            EntryState.IN_ANKI, EntryState.ADDED -> true
            else -> false
        }
}

private enum class EntryState(val js: String) {
    NEW("new"),
    MINING("mining"),
    ADDED("added"),
    IN_ANKI("in-anki"),
    FAILED("failed"),
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
        result.entries.indices.forEach { i ->
            val dup = container.miner.isInAnki(word, result.forEntry(i)) == true
            if (state.entryStates[i] == null) state.entryStates[i] = if (dup) EntryState.IN_ANKI else EntryState.NEW
        }
    }
    return state
}

/**
 * Mines a popup's card: the source's line, sentence and audio, with the card editor's changes
 * when there are any. Hosts without a line (Book, OCR test) leave it null and mine [sentence].
 */
typealias MineCallback = suspend (TermLookup.LookupDetails?, CardEdits?) -> AnkiResult<String>

@Composable
private fun mineAction(
    state: WordLookupState,
    word: String,
    sentence: String,
    image: Bitmap?,
    onMine: MineCallback?,
): (Int, CardEdits?) -> Unit {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    return { index, edits ->
        // Only this entry's headword, reading and definitions go on the card (Yomitan's per-entry add).
        val details = state.cardDetails(index)
        val headword = details?.baseForm ?: word
        state.entryStates[index] = EntryState.MINING
        state.status = null
        // App scope, not the popup's: closing the popup right after tapping must not drop the card.
        container.appScope.launch {
            val result = onMine?.invoke(details, edits) ?: container.miner.mine(word, edits?.sentence ?: sentence, image, details)
            withContext(Dispatchers.Main) {
                state.entryStates[index] = if (result is AnkiResult.Success) EntryState.ADDED else EntryState.FAILED
                state.status = when (result) {
                    is AnkiResult.Success -> null
                    is AnkiResult.Error -> "Anki: ${result.message}"
                    AnkiResult.NotAvailable -> "Anki backend not available"
                }
                // A toast too: the popup may be closed by now, and it is visible over the game either way.
                val toast = when (result) {
                    is AnkiResult.Success -> "✓ Added 「$headword」 to Anki"
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
    onEntryMine: ((Int) -> Unit)? = null,
    onEntryEdit: ((Int) -> Unit)? = null,
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
            // Buttons only exist in the page when there is more than one entry.
            onEntryMine = onEntryMine.takeIf { (state.details?.entries?.size ?: 0) > 1 },
            onEntryEdit = onEntryEdit.takeIf { (state.details?.entries?.size ?: 0) > 1 },
            entryStates = state.entryStates.mapValues { it.value.js },
            onCardPick = state::pick,
            cardPicks = state.cardPicks.toMap(),
        )
    }
}

private val ADDED_GREEN = androidx.compose.ui.graphics.Color(0xFF2E7D32)

/**
 * "+ Anki" and, joined to it, ✎ (the card editor: trim sentence and audio first). The edit half
 * goes away once the word is in Anki or being added.
 */
@Composable
private fun MineButton(state: WordLookupState, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    // Like Yomitan, a word that already has a note shows it instead of offering a duplicate.
    val inAnki = state.inAnki == true
    val addedColors = ButtonDefaults.filledTonalButtonColors(
        disabledContainerColor = ADDED_GREEN,
        disabledContentColor = androidx.compose.ui.graphics.Color.White,
    )
    val enabled = !inAnki && !state.mining && state.details?.terms?.isNotEmpty() == true
    val split = onEdit != null && enabled && !state.added
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row {
        FilledTonalButton(
            enabled = enabled,
            onClick = onClick,
            colors = if (state.added) addedColors else ButtonDefaults.filledTonalButtonColors(),
            shape = if (split) androidx.compose.foundation.shape.RoundedCornerShape(50, 15, 15, 50) else pill,
            contentPadding = if (split) androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 12.dp) else ButtonDefaults.ContentPadding,
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
        if (split) {
            Spacer(Modifier.width(2.dp))
            FilledTonalButton(
                onClick = { onEdit?.invoke() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(15, 50, 50, 15),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 12.dp),
                modifier = Modifier.width(44.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit card before adding", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The editor for entry `index` of the popup's lookup: [openEditor] hosts it (overlay window), or
 * this shows it in a dialog. The edited card is mined like a tap on "+ Anki", so the popup's
 * buttons follow it.
 */
@Composable
private fun editorAction(
    state: WordLookupState,
    word: String,
    sentence: String,
    loadAudio: (suspend () -> AudioSegment?)?,
    mine: (Int, CardEdits?) -> Unit,
    openEditor: ((CardEditRequest) -> Unit)?,
): (Int) -> Unit {
    var request by remember { mutableStateOf<CardEditRequest?>(null) }
    request?.let { r ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { request = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CardEditor(r, onClose = { request = null }, Modifier.padding(16.dp).widthIn(max = 560.dp))
        }
    }
    return { index ->
        val headword = state.details?.entries?.getOrNull(index)?.terms?.firstOrNull()?.expression
        val r = CardEditRequest(
            word = word.takeIf { it in sentence } ?: headword ?: word,
            sentence = sentence,
            loadAudio = loadAudio,
            submit = { edits -> mine(index, edits) },
        )
        if (openEditor != null) openEditor(r) else request = r
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
    onMine: MineCallback? = null,
    /** The sentence audio the card editor trims (the line's clip); null: the source has none. */
    loadAudio: (suspend () -> AudioSegment?)? = null,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    // A word reached from a definition gets that definition's sentence, and no line audio/screenshot.
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 })
    val edit = editorAction(state, entry.word, entry.sentence, loadAudio.takeIf { stack.size == 1 }, mine, openEditor = null)
    val onTap = recursiveTap(stack)
    if (stack.size > 1) androidx.activity.compose.BackHandler { stack.removeAt(stack.lastIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LookupHeader(state, entry.word, onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null) },
        text = {
            // No verticalScroll here: the WebView scrolls itself, and an outer scroll fought it for drags.
            Column(Modifier.heightIn(max = 420.dp)) {
                LookupBody(state, dark, maxHeight = 360.dp, onTextTap = onTap, onEntryMine = { mine(it, null) }, onEntryEdit = edit)
                state.status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { MineButton(state, onClick = { mine(0, null) }, onEdit = { edit(0) }) },
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
    onMine: MineCallback? = null,
    loadAudio: (suspend () -> AudioSegment?)? = null,
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
            loadAudio = loadAudio,
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
    onMine: MineCallback?,
    maxHeight: androidx.compose.ui.unit.Dp,
    handleBack: Boolean,
    width: androidx.compose.ui.unit.Dp = popupWidth(),
    loadAudio: (suspend () -> AudioSegment?)? = null,
    /** Hosts the card editor itself (overlay windows can't show a Compose dialog); null: a dialog. */
    openEditor: ((CardEditRequest) -> Unit)? = null,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val stack = rememberLookupStack(word, sentence)
    val entry = stack.last()
    val state = rememberWordLookup(entry.word, dark)
    val mine = mineAction(state, entry.word, entry.sentence, image.takeIf { stack.size == 1 }, onMine.takeIf { stack.size == 1 })
    val edit = editorAction(state, entry.word, entry.sentence, loadAudio.takeIf { stack.size == 1 }, mine, openEditor)
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
                MineButton(state, onClick = { mine(0, null) }, onEdit = { edit(0) })
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
                LookupBody(state, dark, maxHeight = maxHeight, onTextTap = onTap, onEntryMine = { mine(it, null) }, onEntryEdit = edit)
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
