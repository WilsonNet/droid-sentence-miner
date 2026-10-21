package io.github.wilsonnet.droidsentenceminer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ui.screens.AnkiScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.BookScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.DictionaryScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.HomeScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.SettingsScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.TextsScreen
import io.github.wilsonnet.droidsentenceminer.ui.theme.DsmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DsmTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.mainResumed = true
    }

    override fun onPause() {
        AppVisibility.mainResumed = false
        super.onPause()
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Dictionaries and Anki setup live under Settings; null = the settings list itself.
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = { Text("Book") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Subject, contentDescription = null) },
                    label = { Text("Texts") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = {
                        if (tab == 3) settingsPage = null
                        tab = 3
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(modifier)
            1 -> BookScreen(modifier)
            2 -> TextsScreen(modifier)
            else -> when (settingsPage) {
                PAGE_DICTIONARIES -> SettingsSubPage({ settingsPage = null }, modifier) { DictionaryScreen(it) }
                PAGE_ANKI -> SettingsSubPage({ settingsPage = null }, modifier) { AnkiScreen(it) }
                else -> SettingsScreen(modifier, onOpen = { settingsPage = it })
            }
        }
    }
}

const val PAGE_DICTIONARIES = "dictionaries"
const val PAGE_ANKI = "anki"

@Composable
private fun SettingsSubPage(
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings") }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }
        content(Modifier.weight(1f))
    }
}
