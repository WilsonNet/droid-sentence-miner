package io.github.wilsonnet.droidsentenceminer.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBlock(html: String, dark: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(if (dark) ComposeColor(0xFF1E1E1E) else ComposeColor.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    webViewClient = WebViewClient()
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null)
                }
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
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    var loading by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf<TermLookup.LookupDetails?>(null) }
    var html by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var mining by remember { mutableStateOf(false) }

    LaunchedEffect(word, sentence) {
        loading = true
        val result = container.lookup.lookup(word)
        details = result
        html = container.miner.buildPopupHtml(word, result, dark)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(details?.baseForm ?: word, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                details?.reading?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                details?.deinflection?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking up…")
                    }
                } else {
                    val currentHtml = html
                    if (currentHtml.isNullOrBlank()) {
                        Text("No dictionary entry found. Import a Yomitan dictionary first.")
                    } else {
                        WebViewBlock(
                            html = currentHtml,
                            dark = dark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 360.dp),
                        )
                    }
                }
                status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = !mining && details?.terms?.isNotEmpty() == true,
                onClick = {
                    mining = true
                    status = "Adding to Anki…"
                    scope.launch {
                        val result = container.miner.mine(word, sentence, image, details)
                        status = when (result) {
                            is AnkiResult.Success -> "Added to Anki"
                            is AnkiResult.Error -> "Anki: ${result.message}"
                            AnkiResult.NotAvailable -> "Anki backend not available"
                        }
                        mining = false
                    }
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Anki")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
