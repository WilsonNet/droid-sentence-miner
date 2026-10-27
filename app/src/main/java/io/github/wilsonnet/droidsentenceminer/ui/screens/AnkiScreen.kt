package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.anki.AnkiDroidBridge
import io.github.wilsonnet.droidsentenceminer.anki.AnkiFieldMapper
import io.github.wilsonnet.droidsentenceminer.anki.CardFields
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AnkiScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(DsmPrefs.PROVIDER_ANKIDROID) }
    var url by remember { mutableStateOf(DsmPrefs.DEFAULT_CONNECT_URL) }
    var deck by remember { mutableStateOf(DsmPrefs.DEFAULT_DECK) }
    var model by remember { mutableStateOf(DsmPrefs.DEFAULT_MODEL) }
    var tags by remember { mutableStateOf(DsmPrefs.DEFAULT_TAGS) }
    var includeImage by remember { mutableStateOf(true) }
    var imageMaxHeight by remember { mutableStateOf(DsmPrefs.DEFAULT_IMAGE_MAX_HEIGHT.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var deckOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var deckMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var ankiDroidAvailable by remember { mutableStateOf(false) }
    // Fields editor: the note type it shows, its fields and the content picked for each.
    var fieldsModel by remember { mutableStateOf<String?>(null) }
    var fieldNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var fieldAssignment by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fieldMapCustom by remember { mutableStateOf(false) }
    var fieldsStatus by remember { mutableStateOf<String?>(null) }

    fun loadFields() {
        val target = model.trim()
        scope.launch {
            container.prefs.setAnkiProvider(provider)
            container.prefs.setAnkiConnectUrl(url)
            fieldsStatus = "Loading fields…"
            when (val result = container.ankiClient.bridge().fieldNames(target)) {
                is AnkiResult.Success -> {
                    val saved = container.prefs.ankiFieldMap(target)
                    fieldNames = result.value
                    fieldAssignment = saved?.let { map -> result.value.associateWith { map[it].orEmpty() } }
                        ?: AnkiFieldMapper.automaticAssignment(result.value)
                    fieldMapCustom = saved != null
                    fieldsModel = target
                    fieldsStatus = null
                }
                is AnkiResult.Error -> {
                    fieldsModel = null
                    fieldsStatus = if (target == DsmPrefs.DEFAULT_MODEL) {
                        "${result.message}. It is created with the first card."
                    } else {
                        result.message
                    }
                }
                AnkiResult.NotAvailable -> {
                    fieldsModel = null
                    fieldsStatus = if (provider == DsmPrefs.PROVIDER_ANKIDROID) AnkiDroidBridge.PERMISSION_HINT else "AnkiConnect is not reachable"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val snapshot = container.prefs.snapshot()
        provider = snapshot.ankiProvider
        url = snapshot.ankiConnectUrl
        deck = snapshot.ankiDeck
        model = snapshot.ankiModel
        tags = snapshot.ankiTags
        includeImage = snapshot.ankiIncludeImage
        imageMaxHeight = snapshot.ankiImageMaxHeight.toString()
        ankiDroidAvailable = container.ankiDroid.isAvailable()
        loadFields()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) "AnkiDroid permission granted" else "AnkiDroid permission denied"
        scope.launch { ankiDroidAvailable = container.ankiDroid.isAvailable() }
    }

    // Lists come from the backend selected on screen, which may not be saved yet.
    fun loadOptions(
        what: String,
        fetch: suspend (io.github.wilsonnet.droidsentenceminer.anki.AnkiBridge) -> AnkiResult<List<String>>,
        onLoaded: (List<String>) -> Unit,
    ) {
        scope.launch {
            container.prefs.setAnkiProvider(provider)
            container.prefs.setAnkiConnectUrl(url)
            status = "Loading $what…"
            status = when (val result = fetch(container.ankiClient.bridge())) {
                is AnkiResult.Success -> {
                    onLoaded(result.value)
                    if (result.value.isEmpty()) "No $what found" else null
                }
                is AnkiResult.Error -> result.message
                AnkiResult.NotAvailable -> if (provider == DsmPrefs.PROVIDER_ANKIDROID) {
                    AnkiDroidBridge.PERMISSION_HINT
                } else {
                    "AnkiConnect is not reachable"
                }
            }
        }
    }

    fun saveSettings() {
        scope.launch {
            container.prefs.setAnkiProvider(provider)
            container.prefs.setAnkiConnectUrl(url)
            container.prefs.setAnkiDeck(deck)
            container.prefs.setAnkiModel(model)
            container.prefs.setAnkiTags(tags)
            container.prefs.setAnkiIncludeImage(includeImage)
            container.prefs.setAnkiImageMaxHeight(imageMaxHeight.toIntOrNull() ?: DsmPrefs.DEFAULT_IMAGE_MAX_HEIGHT)
            status = "Settings saved"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Anki", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == DsmPrefs.PROVIDER_ANKIDROID,
                        onClick = { provider = DsmPrefs.PROVIDER_ANKIDROID },
                        label = { Text("AnkiDroid") },
                    )
                    FilterChip(
                        selected = provider == DsmPrefs.PROVIDER_ANKICONNECT,
                        onClick = { provider = DsmPrefs.PROVIDER_ANKICONNECT },
                        label = { Text("AnkiConnect") },
                    )
                }
                if (provider == DsmPrefs.PROVIDER_ANKICONNECT) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("AnkiConnect URL") },
                        supportingText = { Text("Desktop Anki with the AnkiConnect add-on, e.g. http://192.168.1.10:8765") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    val packageName = AddContentApi.getAnkiDroidPackageName(context)
                    if (packageName == null) {
                        Text(
                            "AnkiDroid is not installed.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.ichi2.anki"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Open Play Store") }
                    } else {
                        val granted = ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        Text(
                            if (granted) "AnkiDroid detected and permission granted." else "AnkiDroid detected — permission needed to add cards.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!granted) {
                            Button(onClick = { permissionLauncher.launch(AddContentApi.READ_WRITE_PERMISSION) }) {
                                Text("Grant AnkiDroid permission")
                            }
                        }
                        Text(
                            "Also enable Settings → Advanced → AnkiDroid API in AnkiDroid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Card target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Box {
                    OutlinedTextField(
                        value = deck,
                        onValueChange = { deck = it },
                        label = { Text("Deck") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                deckMenuOpen = true
                                loadOptions("decks", { it.deckNames() }) { deckOptions = it }
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose deck")
                            }
                        },
                    )
                    DropdownMenu(expanded = deckMenuOpen, onDismissRequest = { deckMenuOpen = false }) {
                        deckOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    deck = option
                                    deckMenuOpen = false
                                },
                            )
                        }
                    }
                }

                Box {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Note type") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                modelMenuOpen = true
                                loadOptions("note types", { it.modelNames() }) { modelOptions = it }
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose note type")
                            }
                        },
                    )
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    model = option
                                    modelMenuOpen = false
                                    scope.launch { container.prefs.setAnkiModel(option) }
                                    loadFields()
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeImage, onCheckedChange = { includeImage = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Attach the captured screenshot")
                }

                if (includeImage) {
                    OutlinedTextField(
                        value = imageMaxHeight,
                        onValueChange = { v -> imageMaxHeight = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Picture max height (px)") },
                        supportingText = { Text("Scaled down keeping the aspect ratio. 0 = original size.") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveSettings() }) { Text("Save") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.prefs.setAnkiProvider(provider)
                            container.prefs.setAnkiConnectUrl(url)
                            container.prefs.setAnkiDeck(deck)
                            container.prefs.setAnkiModel(model)
                            container.prefs.setAnkiTags(tags)
                            container.prefs.setAnkiIncludeImage(includeImage)
            container.prefs.setAnkiImageMaxHeight(imageMaxHeight.toIntOrNull() ?: DsmPrefs.DEFAULT_IMAGE_MAX_HEIGHT)
                            status = "Testing…"
                            val bridge = container.ankiClient.bridge()
                            status = when (val result = bridge.testConnection()) {
                                is AnkiResult.Success -> result.value
                                is AnkiResult.Error -> result.message
                                AnkiResult.NotAvailable -> if (provider == DsmPrefs.PROVIDER_ANKIDROID) {
                                    AnkiDroidBridge.PERMISSION_HINT
                                } else {
                                    "AnkiConnect is not reachable"
                                }
                            }
                        }
                    }) { Text("Test connection") }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fields", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val shown = fieldsModel
                if (shown == null || shown != model.trim()) {
                    OutlinedButton(onClick = { loadFields() }) { Text("Load fields of \"${model.trim()}\"") }
                }
                fieldsStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (shown != null) {
                    Text(
                        when {
                            fieldMapCustom -> "Your mapping for \"$shown\"."
                            fieldAssignment.values.all { it.isEmpty() } ->
                                "No field of \"$shown\" matched by name, so cards fill fields in order. Pick what goes where."
                            else -> "Matched by field name. Change any field to set your own mapping."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fieldNames.forEach { field ->
                        FieldMappingRow(field, fieldAssignment[field].orEmpty()) { picked ->
                            fieldAssignment = fieldAssignment + (field to picked)
                            fieldMapCustom = true
                            val map = fieldAssignment
                            scope.launch { container.prefs.setAnkiFieldMap(shown, map) }
                        }
                    }
                    if (fieldMapCustom) {
                        OutlinedButton(onClick = {
                            fieldAssignment = AnkiFieldMapper.automaticAssignment(fieldNames)
                            fieldMapCustom = false
                            scope.launch { container.prefs.setAnkiFieldMap(shown, null) }
                        }) { Text("Reset to automatic") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How cards are created", fontWeight = FontWeight.SemiBold)
                Text(
                    "Fields: Expression, Reading, Meaning, Sentence (with the target word bold), Image, Audio, Source. " +
                        "Other note types are matched by field name (Anime Cards, Lapis, Yomitan-style), or by the mapping above. " +
                        "A note type named \"${DsmPrefs.DEFAULT_MODEL}\" is created automatically if it does not exist.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!ankiDroidAvailable && provider == DsmPrefs.PROVIDER_ANKIDROID) {
                    Text(
                        "AnkiDroid not ready — mine from the popup once permission and the API setting are done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldMappingRow(field: String, canonical: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(field, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(CardFields.label(canonical))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf("") + CardFields.ORDER).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(CardFields.label(option)) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
    }
}
