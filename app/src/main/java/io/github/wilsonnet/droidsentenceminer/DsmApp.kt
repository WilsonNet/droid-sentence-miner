package io.github.wilsonnet.droidsentenceminer

import android.app.Application
import android.content.Context
import io.github.wilsonnet.droidsentenceminer.anki.AnkiClient
import io.github.wilsonnet.droidsentenceminer.anki.AnkiConnectBridge
import io.github.wilsonnet.droidsentenceminer.anki.AnkiDroidBridge
import io.github.wilsonnet.droidsentenceminer.anki.CardMiner
import io.github.wilsonnet.droidsentenceminer.book.BookOcr
import io.github.wilsonnet.droidsentenceminer.book.BookPage
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.DictDownloader
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.dict.YomitanImporter
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrEngine
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
import io.github.wilsonnet.droidsentenceminer.textlog.TextLogDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.wilsonnet.droidsentenceminer.lang.Languages

class DsmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startLanguage()
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
    val appUpdater by lazy { io.github.wilsonnet.droidsentenceminer.update.AppUpdater(context, appScope, prefs) }
    val dictImports by lazy {
        io.github.wilsonnet.droidsentenceminer.dict.DictImportQueue(context, importer, dictDownloader, db, appScope) {
            runCatching { refreshDictUpdates() }
        }
    }

    val ankiDroid: AnkiDroidBridge by lazy { AnkiDroidBridge(context) }
    val ankiConnect: AnkiConnectBridge by lazy { AnkiConnectBridge(prefs) }
    val ankiClient: AnkiClient by lazy { AnkiClient(ankiDroid, ankiConnect, prefs) }
    val textLog: TextLog by lazy { TextLog(context, TextLogDatabase.build(context), prefs) }
    val miner: CardMiner by lazy { CardMiner(context, db, lookup, ankiClient, prefs, textLog) }
    val bookOcr: BookOcr by lazy { BookOcr() }

    /** The last photographed page; kept here so it survives tab switches and rotation. */
    val bookPage = MutableStateFlow<BookPage?>(null)

    /** A photo shared into the app (share sheet → Droid Sentence Miner), waiting for the Book tab to read it. */
    val sharedBookPhoto = MutableStateFlow<android.net.Uri?>(null)

    /** Available dictionary updates keyed by `DictionaryEntity.id`, populated by [refreshDictUpdates]. */
    val dictUpdates = MutableStateFlow<Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryUpdate>>(emptyMap())

    suspend fun refreshDictUpdates(): Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryUpdate> {
        val dicts = db.dictionaryDao().getAll()
        val updates = dictDownloader.checkUpdates(dicts)
        dictUpdates.value = updates
        return updates
    }

    /** The study language's code, null until the user picked one (first-run setup). */
    val languageChoice = MutableStateFlow<String?>(null)

    /** True on a fresh install until the user picks a language (MainActivity shows the setup). */
    val languageSetupNeeded = MutableStateFlow(false)

    /**
     * Reads the study language before anything scans text and follows changes. Installs from
     * before languages existed that already have dictionaries are Japanese, without asking.
     */
    fun startLanguage() {
        val stored = kotlinx.coroutines.runBlocking { prefs.language.first() }
        applyLanguage(stored)
        appScope.launch {
            if (stored == null) {
                if (db.dictionaryDao().count() > 0) prefs.setLanguage(Languages.DEFAULT_CODE) else languageSetupNeeded.value = true
            }
            prefs.language.collect { applyLanguage(it) }
        }
    }

    /** The first-run choice: stores it and leaves the setup screen even if it equals the stored value. */
    suspend fun chooseLanguage(code: String) {
        prefs.setLanguage(code)
        applyLanguage(code)
    }

    private fun applyLanguage(code: String?) {
        val module = Languages.byCode(code)
        if (Languages.current !== module) {
            Languages.current = module
            lookup.invalidateCaches()
        }
        languageChoice.value = code
        if (code != null) languageSetupNeeded.value = false
    }

    /** A language pack download: [progress] 0–100 while running, [error] when it failed. */
    data class PackInstall(val language: String, val progress: Int? = null, val error: String? = null, val version: Int = 0)

    /** The study language's pack download; on [appScope] so it survives leaving Settings. */
    val packInstall = MutableStateFlow<PackInstall?>(null)

    fun installLanguagePack(pack: io.github.wilsonnet.droidsentenceminer.lang.LanguagePack) {
        if (packInstall.value?.progress != null) return
        packInstall.value = PackInstall(pack.language, progress = 0)
        appScope.launch {
            val error = runCatching {
                io.github.wilsonnet.droidsentenceminer.ocr.PaddleOcr.unload()
                io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks.install(context, pack) { p ->
                    packInstall.value = PackInstall(pack.language, progress = p)
                }
            }.exceptionOrNull()?.let { it.message ?: "Download failed" }
            packInstall.value = PackInstall(
                pack.language,
                error = error,
                version = io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks.installedVersion(context, pack.language),
            )
        }
    }

    fun removeLanguagePack(language: String) {
        io.github.wilsonnet.droidsentenceminer.ocr.PaddleOcr.unload()
        io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks.remove(context, language)
        packInstall.value = PackInstall(language, version = 0)
    }

    private val mlKitEngines = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Boolean>, MlKitJapaneseOcr>()

    /**
     * The OCR engine the user picked: meikiocr when selected and downloaded, ML Kit otherwise.
     * [twoPass]/[dropNoise] only apply to ML Kit.
     */
    suspend fun ocrEngine(twoPass: Boolean, dropNoise: Boolean = true, preferred: String? = null): OcrEngine {
        val choice = preferred ?: prefs.ocrEngine.first()
        // A language pack with PaddleOCR models reads that language (English); ML Kit until it's installed.
        Languages.current.pack?.let { pack ->
            if (choice != DsmPrefs.ENGINE_MLKIT && io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks.isInstalled(context, pack)) {
                io.github.wilsonnet.droidsentenceminer.ocr.PaddleOcr.load(
                    io.github.wilsonnet.droidsentenceminer.lang.LanguagePacks.dir(context, pack.language),
                )?.let { return it }
            }
        }
        if (choice == DsmPrefs.ENGINE_MEIKI && Languages.current.supportsMeikiOcr) {
            io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.installVariants(context)
            io.github.wilsonnet.droidsentenceminer.ocr.MeikiOcr.load(
                io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.dir(context),
            )?.let { return it }
        }
        return mlKitEngines.getOrPut(twoPass to dropNoise) { MlKitJapaneseOcr(twoPass = twoPass, dropNoise = dropNoise) }
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as DsmApp).container
    }
}
