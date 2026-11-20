package io.github.wilsonnet.droidsentenceminer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ui.components.ReadableWidth
import io.github.wilsonnet.droidsentenceminer.ui.components.WhatsNewOnUpdate
import io.github.wilsonnet.droidsentenceminer.ui.screens.AiSettingsScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.AnkiScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.BookScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.DictionaryScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.HomeScreen
import io.github.wilsonnet.droidsentenceminer.ui.screens.MangaScreen
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

private const val TAB_MANGA = 1
private const val TAB_BOOK = 2
private const val TAB_TEXTS = 3
private const val TAB_SETTINGS = 4

private val TABS =
    listOf(
        Tab("Home", Icons.Default.Home),
        Tab("Manga", Icons.Default.AutoStories),
        Tab("Book", Icons.AutoMirrored.Filled.MenuBook),
        Tab("Texts", Icons.Default.Subject),
        Tab("Settings", Icons.Default.Settings),
    )

@Suppress("FunctionName")
@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var isMangaReaderOpen by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { AppContainer.from(context) }
    val sharedPhoto by remember { container.sharedBookPhoto }.collectAsState()
    LaunchedEffect(sharedPhoto) {
        if (sharedPhoto != null) {
            isMangaReaderOpen = false
            tab = TAB_BOOK
        }
    }
    val setupNeeded by container.languageSetupNeeded.collectAsState()
    if (setupNeeded) {
        io.github.wilsonnet.droidsentenceminer.ui.screens.LanguageSetupScreen(onDone = { tab = 0 })
        return
    }
    WhatsNewOnUpdate(container)
    MainTabsScaffold(
        tab = tab,
        hideChrome = isMangaReaderOpen && tab == TAB_MANGA,
        onTabChange = { tab = it },
        onMangaReaderOpenChange = { isMangaReaderOpen = it },
    )
}

@Suppress("FunctionName")
@Composable
private fun MainTabsScaffold(
    tab: Int,
    hideChrome: Boolean,
    onTabChange: (Int) -> Unit,
    onMangaReaderOpenChange: (Boolean) -> Unit,
) {
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val select = { index: Int ->
        if (index != TAB_MANGA) onMangaReaderOpenChange(false)
        if (index == TAB_SETTINGS && tab == TAB_SETTINGS) settingsPage = null
        onTabChange(index)
    }

    Scaffold(
        bottomBar = { if (!wide && !hideChrome) AppBottomBar(tab = tab, onSelect = select) },
    ) { padding ->
        Row(if (hideChrome) Modifier else Modifier.padding(padding)) {
            if (wide && !hideChrome) {
                AppSideRail(tab = tab, onSelect = select)
            }
            AppTabContent(
                tab = tab,
                settingsPage = settingsPage,
                onOpenSettingsPage = { settingsPage = it },
                onMangaReaderOpenChange = onMangaReaderOpenChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun AppBottomBar(tab: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        TABS.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = tab == index,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun AppSideRail(tab: Int, onSelect: (Int) -> Unit) {
    NavigationRail(windowInsets = WindowInsets(0)) {
        Spacer(Modifier.weight(1f))
        TABS.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = tab == index,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Suppress("FunctionName")
@Composable
private fun AppTabContent(
    tab: Int,
    settingsPage: String?,
    onOpenSettingsPage: (String?) -> Unit,
    onMangaReaderOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        0 -> {
            ReadableWidth(modifier) { HomeScreen(it) }
        }

        TAB_MANGA -> {
            MangaScreen(modifier) { onMangaReaderOpenChange(it) }
        }

        TAB_BOOK -> {
            BookScreen(modifier)
        }

        TAB_TEXTS -> {
            ReadableWidth(modifier) { TextsScreen(it) }
        }

        else -> {
            ReadableWidth(modifier) { inner ->
                when (settingsPage) {
                    PAGE_DICTIONARIES -> SettingsSubPage({ onOpenSettingsPage(null) }, inner) { DictionaryScreen(it) }
                    PAGE_ANKI -> SettingsSubPage({ onOpenSettingsPage(null) }, inner) { AnkiScreen(it) }
                    PAGE_AI -> SettingsSubPage({ onOpenSettingsPage(null) }, inner) { AiSettingsScreen(it) }
                    else -> SettingsScreen(inner, onOpen = { onOpenSettingsPage(it) })
                }
            }
        }
    }
}

const val PAGE_DICTIONARIES = "dictionaries"
const val PAGE_ANKI = "anki"

/** Settings → AI explanations. */
const val PAGE_AI = "ai"

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
