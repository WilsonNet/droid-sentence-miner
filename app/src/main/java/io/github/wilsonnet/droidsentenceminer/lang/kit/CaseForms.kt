package io.github.wilsonnet.droidsentenceminer.lang.kit

/** The spellings of a word to look up when text capitalizes for reasons the dictionary doesn't. */
object CaseForms {
    /**
     * [text] as written, first letter lowered (sentence starts), all lowered, and, with
     * [capitalized], Capitalized (ALL-CAPS game text for languages that capitalize nouns or names).
     */
    fun spellings(text: String, capitalized: Boolean = true): Set<String> {
        val forms = linkedSetOf(text, text.replaceFirstChar { it.lowercaseChar() }, text.lowercase())
        if (capitalized) forms += text.lowercase().replaceFirstChar { it.uppercaseChar() }
        return forms
    }
}
