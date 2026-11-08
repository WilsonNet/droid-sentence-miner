package io.github.wilsonnet.droidsentenceminer.lang

/**
 * Where word pronunciations come from. Each [LanguageModule] lists only its own sources
 * ([LanguageModule.wordAudioSources]), in the order they are tried: a Japanese learner's cards never
 * query Wikimedia, an English learner's never query JapanesePod101.
 */
enum class WordAudioSource(
    /** Stable name in logs, the debug API and `WordAudio.Clip.source`. */
    val id: String,
) {
    /** JapanesePod101 direct `audiomp3.php` link (Yomitan's first Japanese source). */
    JAPANESE_POD_101("jpod101"),

    /** JapanesePod101 dictionary search. */
    JAPANESE_POD_101_SEARCH("languagepod101"),

    /** Jisho's audio for the word. */
    JISHO("jisho"),

    /** Wiktionary recordings on Wikimedia Commons (`En-us-word.ogg`), Yomitan's source for most languages. */
    WIKTIONARY("wiktionary"),

    /** Lingua Libre recordings on Commons; needs [LanguageModule.linguaLibrePrefix]. */
    LINGUA_LIBRE("lingualibre"),
}
