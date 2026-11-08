package io.github.wilsonnet.droidsentenceminer.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Module-structure rules, checked on every `./gradlew testDebugUnitTest` (and in PR CI). They keep
 * the multilingual design intact as the project grows: generic code talks to `Languages.current`,
 * each language lives in `lang/<code>/`, heavy libraries stay in their layer.
 *
 * If a rule fails, read its message: it names the files and what to do instead. Change a rule only
 * together with docs/architecture.md and `.opencode/skills/dsm-language/SKILL.md`.
 */
class ArchitectureTest {
    private val production: List<KoFileDeclaration> by lazy {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("/droidsentenceminer/") }
    }

    private fun KoFileDeclaration.rel(): String = path.substringAfter("/droidsentenceminer/")

    private fun KoFileDeclaration.importNames(): List<String> = imports.map { it.name }

    private fun KoFileDeclaration.isInLangFramework(): Boolean {
        val path = rel()
        return path.startsWith("lang/") && path.count { it == '/' } == 1
    }

    private fun KoFileDeclaration.languagePackageOrNull(): String? {
        val parts = rel().split('/')
        return if (parts.size >= LANGUAGE_PATH_PARTS && parts[0] == "lang") parts[1] else null
    }

    private fun KoFileDeclaration.importsOfLanguages(): List<String> =
        importNames().filter { LANG_PACKAGE.containsMatchIn(it) }

    private fun failIfAny(rule: String, offenders: List<String>, fix: String) {
        val list = offenders.joinToString(separator = "\n    ")
        val message =
            """
                |$rule
                |  offenders:
                |    $list
                |  fix: $fix
            """.trimMargin()
        assertTrue(message, offenders.isEmpty())
    }

    @Test
    fun `generic code never imports a language module directly`() {
        val offenders =
            production
                .filter { !it.rel().startsWith("lang/") }
                .flatMap { file -> file.importsOfLanguages().map { "${file.rel()}: $it" } }
        failIfAny(
            rule = "Only lang/ may import lang.<code> packages.",
            offenders = offenders,
            fix = "ask Languages.current (LanguageModule); add a method with a default to LanguageModule if needed",
        )
    }

    @Test
    fun `language modules do not depend on each other`() {
        val offenders =
            production.mapNotNull { file ->
                val own = file.languagePackageOrNull()
                val others =
                    file
                        .importNames()
                        .mapNotNull { LANG_PACKAGE.find(it)?.run { groupValues[1] } }
                        .filter { it != own }
                if (own == null || others.isEmpty()) null else "${file.rel()} imports lang.${others.joinToString()}"
            }
        failIfAny(
            rule = "A language package imports only itself and generic code.",
            offenders = offenders,
            fix = "move shared logic to lang/ or a generic package",
        )
    }

    @Test
    fun `the lang framework only names language modules in the registry`() {
        val offenders =
            production
                .filter { it.isInLangFramework() && !it.rel().endsWith(REGISTRY_FILE) }
                .flatMap { file -> file.importsOfLanguages().map { "${file.rel()}: $it" } }
        failIfAny(
            rule = "lang/*.kt (framework) must stay language-neutral; only Languages.ALL lists modules.",
            offenders = offenders,
            fix = "register modules in $REGISTRY_FILE only",
        )
    }

    @Test
    fun `every language module lives in its package and is registered`() {
        val declared =
            production.mapNotNull { file ->
                MODULE_DECLARATION.find(file.text)?.let { match -> match.groupValues[1] to file }
            }
        val misplaced =
            declared
                .filter { (_, file) -> file.languagePackageOrNull() == null }
                .map { (name, file) -> "$name in ${file.rel()}" }
        failIfAny(
            rule = "LanguageModule implementations live in lang/<code>/.",
            offenders = misplaced,
            fix = "move it to lang/<code>/<Name>Language.kt",
        )
        failIfAny(
            rule = "Language modules are objects named <Language>Language.",
            offenders = declared.map { it.first }.filter { !it.endsWith("Language") },
            fix = "rename it, e.g. SpanishLanguage",
        )
        val registered = Languages.ALL.map { it::class.simpleName }.toSet()
        failIfAny(
            rule = "Every language module is in Languages.ALL.",
            offenders = declared.map { it.first }.filter { it !in registered },
            fix = "add it to Languages.ALL in $REGISTRY_FILE",
        )
        val codes = Languages.ALL.map { it.code }
        assertEquals("Language codes must be unique", codes.size, codes.toSet().size)
        Languages.ALL.forEach { language ->
            val isValid = language.code.matches(ISO_639_1)
            assertTrue("${language.displayName}: code '${language.code}' must be ISO 639-1", isValid)
        }
    }

    @Test
    fun `generic code does not hard-code Japanese script checks`() {
        val offenders =
            production
                .filter { !it.rel().startsWith("lang/") && it.rel() !in SCRIPT_ALLOWLIST }
                .flatMap { file -> SCRIPT_PATTERNS.filter { file.text.contains(it) }.map { "${file.rel()}: $it" } }
        failIfAny(
            rule = "Kana/kanji checks belong to lang/ja (generic code asks LanguageModule.isTargetChar/isLookupable/isRubyChar).",
            offenders = offenders,
            fix = "use Languages.current, or add a LanguageModule hook",
        )
    }

    @Test
    fun `heavy libraries stay in their layer`() {
        val offenders =
            production.flatMap { file ->
                LAYERS
                    .asSequence()
                    .filter { (_, allowed) -> allowed.none { file.rel().startsWith(it) } }
                    .flatMap { (prefix, _) -> file.importNames().filter { it.startsWith(prefix) } }
                    .map { "${file.rel()}: $it" }
                    .toList()
            }
        failIfAny(
            rule = "ONNX Runtime and ML Kit are used only by ocr/; the AnkiDroid API only by anki/ and its settings screen.",
            offenders = offenders,
            fix = "go through OcrEngine / AnkiClient",
        )
    }

    @Test
    fun `main code never references the debug-only API`() {
        val offenders =
            production
                .filter { !it.path.contains("/src/debug/") }
                .flatMap { file ->
                    file
                        .importNames()
                        .filter { it.startsWith("$ROOT.debug.") }
                        .map { "${file.rel()}: $it" }
                }
        failIfAny(
            rule = "The debug API is booted by reflection (DsmApp); main code must not import it.",
            offenders = offenders,
            fix = "keep debug-only code in src/debug",
        )
    }

    @Test
    fun `heavy data is downloaded, not bundled`() {
        val tooBig =
            File("src/main/assets")
                .walkTopDown()
                .filter { it.isFile && it.length() > MAX_ASSET_BYTES }
                .map { "${it.path} (${it.length() / KIB} KB)" }
                .toList()
        failIfAny(
            rule = "Assets over ${MAX_ASSET_BYTES / KIB} KB bloat the APK for every language.",
            offenders = tooBig,
            fix = "ship models/dictionaries as a language pack (tools/lang-packs) or a recommended download",
        )
    }

    private companion object {
        const val ROOT = "io.github.wilsonnet.droidsentenceminer"
        const val REGISTRY_FILE = "lang/Language.kt"
        const val LANGUAGE_PATH_PARTS = 3
        const val KIB = 1024L

        // The meikiocr graph cuts (≤ 755 KB each, Japanese-only, 3.6 MB together) are the largest
        // assets today; moving them into a Japanese language pack is on the roadmap.
        const val MAX_ASSET_BYTES = 1024 * KIB

        val LANG_PACKAGE = Regex("""^io\.github\.wilsonnet\.droidsentenceminer\.lang\.([a-z]{2})(\.|$)""")
        val MODULE_DECLARATION = Regex("""object\s+(\w+)\s*:\s*LanguageModule\b""")
        val ISO_639_1 = Regex("[a-z]{2}")

        // Script checks that predate lang/: TermLookup's isKana/isKanji serve kanji dictionaries.
        val SCRIPT_ALLOWLIST = setOf("dict/TermLookup.kt")
        val SCRIPT_PATTERNS =
            listOf(
                "UnicodeScript.HIRAGANA",
                "UnicodeScript.KATAKANA",
                "UnicodeScript.HAN",
                "IsHiragana",
                "IsKatakana",
                "0x3040",
            )

        val LAYERS =
            listOf(
                "ai.onnxruntime" to listOf("ocr/", "debug/"),
                "com.google.mlkit" to listOf("ocr/", "debug/"),
                "com.ichi2" to listOf("anki/", "ui/screens/AnkiScreen.kt", "debug/"),
            )
    }
}
