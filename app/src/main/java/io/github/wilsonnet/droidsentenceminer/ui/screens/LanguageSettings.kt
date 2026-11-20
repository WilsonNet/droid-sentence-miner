package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.lang.LookupOption
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource
import kotlinx.coroutines.launch

/**
 * Settings that only exist for some study languages, so a learner never sees another language's
 * options: the language's lookup options ([LanguageModule.lookupOptions]), and the texts Settings
 * words per language (word-audio sources, custom audio URL placeholders, when ML Kit options apply).
 */
internal object LanguageSettings {
    private val SOURCE_NAMES =
        mapOf(
            WordAudioSource.JAPANESE_POD_101 to "JapanesePod101",
            WordAudioSource.JAPANESE_POD_101_SEARCH to "JapanesePod101 search",
            WordAudioSource.JISHO to "Jisho",
            WordAudioSource.WIKTIONARY to "Wiktionary recordings",
            WordAudioSource.LINGUA_LIBRE to "Lingua Libre",
            WordAudioSource.WIKTIONARY_PAGE to "the word's LANGUAGE Wiktionary page",
        )

    /** The "Word audio" switch's description, naming only the study language's sources in order. */
    fun wordAudioDescription(language: LanguageModule): String {
        val sources =
            language.wordAudioSources.map { source ->
                SOURCE_NAMES.getValue(source).replace(oldValue = "LANGUAGE", newValue = language.displayName)
            }
        val chain = sources.joinToString(", then ").ifEmpty { "no source yet" }
        return "Add the word's pronunciation to the card: $chain. Needs internet when mining."
    }

    /** An example custom audio URL: {reading} only where cards have a reading (Japanese kana). */
    fun customAudioPlaceholder(language: LanguageModule): String =
        if (language.hasIpaReading) "http://127.0.0.1:5050/?term={term}" else "http://127.0.0.1:5050/?term={term}&reading={reading}"

    /** The custom audio URL's help text, listing the placeholders this language fills. */
    fun customAudioHelp(language: LanguageModule): String {
        val fields = if (language.hasIpaReading) "{term} and {language}" else "{term}, {reading} and {language}"
        return "Tried first, e.g. a local audio server. $fields are replaced."
    }

    /**
     * Whether ML Kit reads this language's screen text (its options apply): Japanese with the
     * ML Kit engine or without the meikiocr models, other languages until their OCR pack is installed.
     */
    fun usesMlKit(context: Context, language: LanguageModule, engine: String, isMeikiInstalled: Boolean): Boolean {
        val pack = language.pack
        return when {
            language.supportsMeikiOcr -> engine != DsmPrefs.ENGINE_MEIKI || !isMeikiInstalled
            pack == null -> true
            else -> LanguagePacks.installedVersion(context, pack.language) < pack.version
        }
    }
}

/**
 * The study language's lookup options (German: separable verbs, compound splitting), as switches;
 * nothing at all for a language without options.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun LanguageLookupOptionsCard(languageCode: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val language = Languages.byCode(languageCode)
    val disabled by container.lookupOptions.disabled.collectAsState(initial = emptySet())
    if (language.lookupOptions.isEmpty()) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${language.displayName} lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (option in language.lookupOptions) {
                LookupOptionRow(option = option, isOn = option.feature !in disabled, container = container)
            }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun LookupOptionRow(option: LookupOption, isOn: Boolean, container: AppContainer) {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(option.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isOn,
            onCheckedChange = { value ->
                scope.launch { container.lookupOptions.setEnabled(feature = option.feature, isEnabled = value) }
            },
        )
    }
}
