package io.github.wilsonnet.droidsentenceminer.lang

/**
 * A [LookupFeature] a language offers as a user setting (Settings shows a language's options only
 * while it is the study language), on unless the user turns it off.
 *
 * @property feature the engine behavior it switches.
 * @property title the setting's name.
 * @property description what it does and its trade-off, shown under the title.
 */
data class LookupOption(val feature: LookupFeature, val title: String, val description: String)
