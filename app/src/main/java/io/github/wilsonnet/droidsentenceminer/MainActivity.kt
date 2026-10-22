package io.github.wilsonnet.droidsentenceminer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ui.components.ReadableWidth
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

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Home", Icons.Default.Home),
    Tab("Book", Icons.AutoMirrored.Filled.MenuBook),
    Tab("Texts", Icons.Default.Subject),
    Tab("Settings", Icons.Default.Settings),
)

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Dictionaries and Anki setup live under Settings; null = the settings list itself.
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    // Tablets and landscape phones: tabs in a side rail, so the bottom bar doesn't eat the short side.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val select = { index: Int ->
        if (index == 3 && tab == 3) settingsPage = null
        tab = index
    }

    Scaffold(
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    TABS.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { select(index) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding)) {
            if (wide) {
                NavigationRail(windowInsets = WindowInsets(0)) {
                    Spacer(Modifier.weight(1f))
                    TABS.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = tab == index,
                            onClick = { select(index) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            val modifier = Modifier.weight(1f)
            when (tab) {
                0 -> ReadableWidth(modifier) { HomeScreen(it) }
                1 -> BookScreen(modifier)
                2 -> ReadableWidth(modifier) { TextsScreen(it) }
                else -> ReadableWidth(modifier) { inner ->
                    when (settingsPage) {
                        PAGE_DICTIONARIES -> SettingsSubPage({ settingsPage = null }, inner) { DictionaryScreen(it) }
                        PAGE_ANKI -> SettingsSubPage({ settingsPage = null }, inner) { AnkiScreen(it) }
                        else -> SettingsScreen(inner, onOpen = { settingsPage = it })
                    }
                }
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
