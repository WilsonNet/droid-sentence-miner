package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.dict.DictDownloader
import io.github.wilsonnet.droidsentenceminer.dict.DriveLinks
import kotlinx.coroutines.launch

/** A file to import from a public Drive link. */
data class DriveImport(
    val fileId: String,
    val name: String?,
    /** Optional Drive file ID of a matching `.mokuro` sidecar file. */
    val mokuroFileId: String? = null,
)

/** Controls what files the Google Drive sheet filters for and where recent links are stored. */
enum class DriveSheetTarget {
    /** Dictionary zip archives. */
    DICTIONARIES,

    /** `.cbz`/`.zip` manga volumes and `.mokuro` sidecars. */
    MANGA,
}

private const val DRIVE_PACKAGE = "com.google.android.apps.docs"

/**
 * "Google Drive" bottom sheet: browse your own Drive through Android's file picker (the Drive app
 * signs you in), or open a shared file/folder link and pick dictionaries or `.cbz` manga volumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveImportSheet(
    onDismiss: () -> Unit,
    onBrowseMyDrive: () -> Unit,
    onInstall: (List<DriveImport>) -> Unit,
    target: DriveSheetTarget = DriveSheetTarget.DICTIONARIES,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val driveInstalled = remember { context.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE) != null }
    val recentFlow =
        remember(target) {
            if (target == DriveSheetTarget.MANGA) container.prefs.mangaDriveRecent else container.prefs.driveRecent
        }
    val recent by recentFlow.collectAsState(initial = emptyList())

    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Folder navigation: the opened link is the root, subfolders push onto the stack.
    val stack = remember { mutableStateListOf<DictDownloader.DriveFolder>() }
    var sharedFile by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }

    fun openFolder(folderId: String, rootLink: String?) {
        loading = true
        error = null
        scope.launch {
            try {
                val folder = container.dictDownloader.listDriveFolder(folderId)
                if (rootLink != null) {
                    stack.clear()
                    selected.clear()
                    val title = folder.title ?: "Shared folder"
                    if (target == DriveSheetTarget.MANGA) {
                        container.prefs.addMangaDriveRecent(link = rootLink, name = title)
                    } else {
                        container.prefs.addDriveRecent(rootLink, title)
                    }
                }
                stack += folder
            } catch (e: Exception) {
                error = e.message ?: "Could not open the folder"
            } finally {
                loading = false
            }
        }
    }

    fun openLink(text: String) {
        error = null
        sharedFile = null
        when (val parsed = DriveLinks.parse(text)) {
            null -> error = "That doesn't look like a Google Drive link. Copy it with Share → Copy link in Drive."
            is DriveLinks.Link.Folder -> openFolder(parsed.id, DriveLinks.folderUrl(parsed.id))
            is DriveLinks.Link.File -> {
                stack.clear()
                selected.clear()
                sharedFile = parsed.id
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val current = stack.lastOrNull()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Google Drive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }

            if (current == null) {
                item {
                    MyDriveCard(
                        driveInstalled = driveInstalled,
                        target = target,
                        onBrowse = {
                            onBrowseMyDrive()
                            onDismiss()
                        },
                        onOpenDriveApp = { openDriveApp(context, driveInstalled) },
                    )
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shared link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (target == DriveSheetTarget.MANGA) {
                            "Paste a link to a .cbz manga or a folder of them (matching .mokuro files are paired automatically). " +
                                "It has to be shared as \"Anyone with the link\"; no sign-in needed."
                        } else {
                            "Paste a link to a dictionary .zip or to a folder of them. It has to be shared as \"Anyone with the link\"; no sign-in needed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://drive.google.com/drive/folders/…") },
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { pasted ->
                                    link = pasted.trim()
                                    if (DriveLinks.parse(pasted) != null) openLink(pasted)
                                }
                            }) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste link") }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { openLink(link) }),
                        isError = error != null,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(enabled = link.isNotBlank() && !loading, onClick = { openLink(link) }) {
                            Text("Open link")
                        }
                        if (loading) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (current == null && sharedFile == null && recent.isNotEmpty()) {
                        Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recent.forEach { (recentLink, name) ->
                            InputChip(
                                selected = false,
                                onClick = { link = recentLink; openLink(recentLink) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Forget",
                                        modifier =
                                        Modifier.size(18.dp).clickable {
                                            scope.launch {
                                                if (target == DriveSheetTarget.MANGA) {
                                                    container.prefs.removeMangaDriveRecent(recentLink)
                                                } else {
                                                    container.prefs.removeDriveRecent(recentLink)
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            sharedFile?.let { fileId ->
                item {
                    Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Shared file", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (target == DriveSheetTarget.MANGA) {
                                        "Downloads and adds it to your manga library."
                                    } else {
                                        "Downloads and imports it as a Yomitan dictionary."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = {
                                onInstall(listOf(DriveImport(fileId, null)))
                                onDismiss()
                            }) { Text(if (target == DriveSheetTarget.MANGA) "Add" else "Install") }
                        }
                    }
                }
            }

            if (current != null) {
                val files =
                    current.entries.filter {
                        if (target == DriveSheetTarget.MANGA) it.isCbz else it.isZip
                    }
                val mokurosByStem =
                    if (target == DriveSheetTarget.MANGA) {
                        current.entries.filter { it.isMokuro }.associateBy { it.stem }
                    } else {
                        emptyMap()
                    }
                val folders = current.entries.filter { it.isFolder }
                val mokuroCount = if (target == DriveSheetTarget.MANGA) current.entries.count { it.isMokuro } else 0
                val others = current.entries.size - files.size - folders.size - mokuroCount
                item {
                    HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 20.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            stack.removeAt(stack.lastIndex)
                            selected.clear()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stack.joinToString(" › ") { it.title ?: "Folder" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (target == DriveSheetTarget.MANGA) {
                                        append("${files.size} volume${if (files.size == 1) "" else "s"}")
                                        val paired = files.count { it.stem in mokurosByStem }
                                        if (paired > 0) append(" ($paired with .mokuro)")
                                    } else {
                                        append("${files.size} dictionar${if (files.size == 1) "y" else "ies"}")
                                    }
                                    if (folders.isNotEmpty()) append(" · ${folders.size} folder${if (folders.size == 1) "" else "s"}")
                                    if (others > 0) append(" · $others other file${if (others == 1) "" else "s"} hidden")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (files.isNotEmpty()) {
                            val hasAll = files.all { it.id in selected }
                            TextButton(onClick = {
                                selected.clear()
                                if (!hasAll) selected += files.map { it.id }
                            }) { Text(if (hasAll) "Select none" else "Select all") }
                        }
                    }
                }
                if (current.entries.isEmpty()) {
                    item {
                        Text(
                            "This folder is empty.",
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(folders, key = { "f-" + it.id }) { entry ->
                    DriveEntryRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                        name = entry.name,
                        modified = entry.modified,
                        enabled = !loading,
                        onClick = { openFolder(entry.id, rootLink = null) },
                    )
                }
                items(files, key = { "z-" + it.id }) { entry ->
                    val checked = entry.id in selected
                    val pairedMokuro = mokurosByStem[entry.stem]
                    val displayName = entry.name.removeSuffix(".cbz").removeSuffix(".zip")
                    val subtitle =
                        buildString {
                            if (pairedMokuro != null) append("Paired with .mokuro")
                            entry.modified?.let { modified ->
                                if (isNotEmpty()) append(" · ")
                                append(modified)
                            }
                        }.ifBlank { null }
                    DriveEntryRow(
                        icon = { Icon(Icons.Default.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        name = displayName,
                        modified = subtitle,
                        enabled = true,
                        onClick = { if (checked) selected -= entry.id else selected += entry.id },
                        trailing = {
                            Checkbox(checked = checked, onCheckedChange = { if (it) selected += entry.id else selected -= entry.id })
                        },
                    )
                }
                if (files.isNotEmpty()) {
                    item {
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                onInstall(
                                    files.filter { it.id in selected }.map { entry ->
                                        DriveImport(
                                            fileId = entry.id,
                                            name = entry.name,
                                            mokuroFileId = mokurosByStem[entry.stem]?.id,
                                        )
                                    },
                                )
                                onDismiss()
                            },
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                if (target == DriveSheetTarget.MANGA) {
                                    when (selected.size) {
                                        0 -> "Select volumes to download"
                                        1 -> "Download 1 volume"
                                        else -> "Download ${selected.size} volumes"
                                    }
                                } else {
                                    when (selected.size) {
                                        0 -> "Select dictionaries to install"
                                        1 -> "Install 1 dictionary"
                                        else -> "Install ${selected.size} dictionaries"
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

@Composable
private fun MyDriveCard(
    driveInstalled: Boolean,
    target: DriveSheetTarget,
    onBrowse: () -> Unit,
    onOpenDriveApp: () -> Unit,
) {
    val noun = if (target == DriveSheetTarget.MANGA) "manga (.cbz / .mokuro)" else "dictionaries"
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your Drive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (driveInstalled) {
                    "Pick $noun from your own Drive, private files included: in the picker, choose Drive (top row or the ☰ menu). " +
                        "You stay signed in through the Google Drive app; DSM never sees your password."
                } else {
                    "To browse your own Drive, install the Google Drive app and sign in there. It then shows up in the " +
                        "file picker. Shared links below work without it."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (driveInstalled) {
                    Button(onClick = onBrowse) { Text("Browse my Drive") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onOpenDriveApp) { Text("Sign in / switch account") }
                } else {
                    Button(onClick = onOpenDriveApp) { Text("Get Google Drive") }
                }
            }
        }
    }
}

@Composable
private fun DriveEntryRow(
    icon: @Composable () -> Unit,
    name: String,
    modified: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            modified?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** Opens the Drive app (where accounts are added/switched), or its Play Store page. */
private fun openDriveApp(context: Context, installed: Boolean) {
    val intent = if (installed) {
        context.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE)
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$DRIVE_PACKAGE"))
    } ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$DRIVE_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
