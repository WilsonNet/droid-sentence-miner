package io.github.wilsonnet.droidsentenceminer

import android.app.Application
import android.content.Context
import io.github.wilsonnet.droidsentenceminer.anki.AnkiClient
import io.github.wilsonnet.droidsentenceminer.anki.AnkiConnectBridge
import io.github.wilsonnet.droidsentenceminer.anki.AnkiDroidBridge
import io.github.wilsonnet.droidsentenceminer.anki.CardMiner
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.DictDownloader
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.dict.YomitanImporter
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DsmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Debug builds only: boot the loopback text API (see DebugApi in src/debug).
        runCatching {
            val apiClass = Class.forName("io.github.wilsonnet.droidsentenceminer.debug.DebugApi")
            val api = apiClass.getField("INSTANCE").get(null)
            apiClass.getMethod("boot", Application::class.java).invoke(api, this)
        }
    }
}

/** Hand-rolled service locator; keeps the app dependency-light. */
class AppContainer(private val context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: DictDatabase by lazy { DictDatabase.build(context) }
    val prefs: DsmPrefs by lazy { DsmPrefs(context) }
    val lookup: TermLookup by lazy { TermLookup(db) }
    val importer: YomitanImporter by lazy { YomitanImporter(context, db) }
    val dictDownloader: DictDownloader by lazy { DictDownloader() }

    val ankiDroid: AnkiDroidBridge by lazy { AnkiDroidBridge(context) }
    val ankiConnect: AnkiConnectBridge by lazy { AnkiConnectBridge(prefs) }
    val ankiClient: AnkiClient by lazy { AnkiClient(ankiDroid, ankiConnect, prefs) }
    val miner: CardMiner by lazy { CardMiner(context, db, lookup, ankiClient, prefs) }

    fun ocrEngine(twoPass: Boolean): OcrEngine = MlKitJapaneseOcr(twoPass = twoPass)

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as DsmApp).container
    }
}
