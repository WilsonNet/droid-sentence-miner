package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.BuildConfig
import io.github.wilsonnet.droidsentenceminer.update.AppUpdater

/** Home's banner: shown only while a newer release is available, downloading or ready to install. */
@Composable
fun AppUpdateBanner(updater: AppUpdater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val release = when (val s = state) {
        is AppUpdater.State.Available -> s.release
        is AppUpdater.State.Downloading -> s.release
        is AppUpdater.State.Ready -> s.release
        is AppUpdater.State.Failed -> s.release
        else -> null
    } ?: return
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: ${release.tag}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "You have ${BuildConfig.VERSION_NAME}. Your dictionaries, settings and history are kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            AppUpdateControls(updater, state)
        }
    }
}

/** Settings → About: the version, a manual check and the same update button. */
@Composable
fun AppUpdateRow(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val s = state) {
            AppUpdater.State.UpToDate -> Text("You have the latest version.", style = MaterialTheme.typography.bodySmall)
            is AppUpdater.State.Available, is AppUpdater.State.Downloading, is AppUpdater.State.Ready ->
                Text("Update available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is AppUpdater.State.Failed -> if (s.release == null) {
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            else -> Unit
        }
        AppUpdateControls(updater, state)
    }
}

@Composable
private fun AppUpdateControls(updater: AppUpdater, state: AppUpdater.State) {
    when (state) {
        is AppUpdater.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Downloading ${state.release.tag}…", style = MaterialTheme.typography.bodySmall)
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is AppUpdater.State.Available -> Button(onClick = { updater.update(state.release) }) { Text("Update to ${state.release.tag}") }
        is AppUpdater.State.Ready -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Confirm in the installer. If Android asked to allow installs from this app, allow it and tap Install again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { updater.update(state.release) }) { Text("Install ${state.release.tag}") }
        }
        is AppUpdater.State.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.release != null) {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                Button(onClick = { updater.update(state.release) }) { Text("Retry") }
            } else {
                OutlinedButton(onClick = { updater.check() }) { Text("Check for updates") }
            }
        }
        AppUpdater.State.Checking -> OutlinedButton(onClick = {}, enabled = false) { Text("Checking…") }
        else -> OutlinedButton(onClick = { updater.check() }) { Text("Check for updates") }
    }
}
