package io.github.wilsonnet.droidsentenceminer.dict

/** The download size for the Dictionaries page: "~17 MB", or "<1 MB" for the small glossaries. */
fun RecommendedDictionary.sizeLabel(): String =
    if (approximateSizeMb < 1.0) "<1 MB" else "~${approximateSizeMb.toInt()} MB"
