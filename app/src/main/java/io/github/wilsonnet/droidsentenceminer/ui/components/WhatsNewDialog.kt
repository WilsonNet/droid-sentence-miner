package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.update.ChangelogSection
import io.github.wilsonnet.droidsentenceminer.update.WhatsNew
import kotlinx.coroutines.launch

private const val MAX_HEIGHT_DP = 520

/**
 * The sections a *What's new* dialog shows, as one stable value for Compose.
 *
 * @property sections Newest first.
 */
@Immutable
data class ChangelogNotes(val sections: List<ChangelogSection>)

/**
 * Once per update: loads this version's changelog into [AppContainer.whatsNew] (never on a fresh
 * install) and shows it until *Got it*, which records the version.
 */
@Suppress("FunctionName") // Composables are PascalCase (detekt's ignoreWhenAnnotatedWith doesn't reach this rule).
@Composable
fun WhatsNewOnUpdate(container: AppContainer) {
    val whatsNew = container.whatsNew
    val prefs = container.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { whatsNew.value = WhatsNew.pending(context = context, prefs = prefs) }
    val sections by whatsNew.collectAsState()
    if (sections.isNotEmpty()) {
        WhatsNewDialog(
            notes = ChangelogNotes(sections),
            onDismiss = {
                whatsNew.value = emptyList()
                scope.launch { prefs.setWhatsNewSeen(WhatsNew.versionKey) }
            },
        )
    }
}

/** The *What's new* dialog: the changelog sections of this update, dismissed with *Got it*. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun WhatsNewDialog(
    notes: ChangelogNotes,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("What's new") },
        text = { SectionList(notes) },
    )
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun SectionList(notes: ChangelogNotes) {
    Column(
        Modifier.heightIn(max = MAX_HEIGHT_DP.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notes.sections.forEach { section -> SectionView(section) }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun SectionView(section: ChangelogSection) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val title = listOfNotNull("Version ${section.version}", section.date).joinToString(" · ")
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        for (block in ChangelogBlocks.parse(section.body)) {
            when (block) {
                is ChangelogBlocks.Block.Heading -> {
                    Text(block.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }

                is ChangelogBlocks.Block.Bullet -> {
                    Row {
                        Text("•", Modifier.width(14.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(styled(block.text), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is ChangelogBlocks.Block.Paragraph -> {
                    Text(styled(block.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Suppress("FunctionSignature") // Multi-line body: ktlint's function-signature and multiline-expression-wrapping disagree (dsm-lint).
private fun styled(text: String): AnnotatedString =
    buildAnnotatedString {
        for (piece in ChangelogBlocks.inline(text)) {
            val style =
                when (piece.style) {
                    ChangelogBlocks.Inline.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    ChangelogBlocks.Inline.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    ChangelogBlocks.Inline.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
                    ChangelogBlocks.Inline.PLAIN -> null
                }
            if (style == null) append(piece.text) else withStyle(style) { append(piece.text) }
        }
    }
