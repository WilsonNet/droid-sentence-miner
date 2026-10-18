package io.github.wilsonnet.droidsentenceminer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.wilsonnet.droidsentenceminer.ui.screens.AnkiScreen
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
                    icon = { Icon(Icons.Default.Subject, contentDescription = null) },
                    label = { Text("Texts") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Book, contentDescription = null) },
                    label = { Text("Dicts") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Style, contentDescription = null) },
                    label = { Text("Anki") },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(modifier)
            1 -> TextsScreen(modifier)
            2 -> DictionaryScreen(modifier)
            3 -> AnkiScreen(modifier)
            else -> SettingsScreen(modifier)
        }
    }
}
