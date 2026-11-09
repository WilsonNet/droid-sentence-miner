package io.github.wilsonnet.droidsentenceminer.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.wilsonnet.droidsentenceminer.BuildConfig
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One `## [x.y.z] — date` section of CHANGELOG.md.
 *
 * @property version The release, "0.7.0".
 * @property date The release date as written, null when the header has none.
 * @property body The section's Markdown below the header.
 */
data class ChangelogSection(val version: String, val date: String?, val body: String)

/** Parses CHANGELOG.md (Keep a Changelog) and picks what an updated app should show. Pure Kotlin, unit tested. */
object Changelog {
    private val HEADER = Regex("""^## \[([^]]+)](?:\s*[—–-]\s*(.+))?\s*$""")

    // Markdown link reference definitions (`[0.6.0]: https://…compare/…`) at the end of the file.
    private val LINK_DEFINITION = Regex("""^\[[^]]+]:\s+\S+.*$""")
    private const val UNRELEASED = "Unreleased"
    private const val MAX_SECTIONS = 5

    /** Every released section, newest first (the file's order); *Unreleased* and empty sections are skipped. */
    fun sections(markdown: String): List<ChangelogSection> {
        val out = mutableListOf<ChangelogSection>()
        var header: MatchResult? = null
        val body = StringBuilder()
        for (line in markdown.lines() + "## [$UNRELEASED]") {
            val match = HEADER.matchEntire(line)
            if (match == null) {
                if (header != null && !LINK_DEFINITION.matches(line)) body.appendLine(line)
                continue
            }
            val section = header?.let { done -> sectionOf(header = done, body = body.toString()) }
            if (section != null) out += section
            header = match
            body.clear()
        }
        return out
    }

    private fun sectionOf(header: MatchResult, body: String): ChangelogSection? {
        val version = header.groupValues[1].trim()
        val text = body.trim()
        val isReleased = !version.equals(UNRELEASED, ignoreCase = true) && text.isNotEmpty()
        val date = header.groupValues[2].trim().ifEmpty { null }
        return if (isReleased) ChangelogSection(version = version, date = date, body = text) else null
    }

    /**
     * What to show after updating from [seen] to [current]: every section newer than [seen] up to
     * [current] (an update can skip releases; at most five), only [current]'s when nothing was seen
     * yet (updated from a build before this dialog existed), or the newest section when none of
     * that is in the changelog (a local build).
     */
    fun forUpdate(markdown: String, seen: String?, current: String): List<ChangelogSection> {
        val all = sections(markdown)
        val inRange =
            all.filter { section ->
                val isUpToCurrent = AppVersion.compare(a = section.version, b = current) <= 0
                val isUnseen =
                    if (seen == null) {
                        AppVersion.compare(a = section.version, b = current) == 0
                    } else {
                        AppVersion.compare(a = section.version, b = seen) > 0
                    }
                isUpToCurrent && isUnseen
            }
        return inRange.take(MAX_SECTIONS).ifEmpty { all.take(1) }
    }
}

/**
 * The one-time *What's new* dialog after an update: shown once per app version (pref
 * `whats_new_seen`), never on a fresh install (that silently records the version). The changelog
 * is CHANGELOG.md, bundled as an asset at build time (`copyChangelog` in app/build.gradle.kts).
 */
object WhatsNew {
    private const val ASSET = "CHANGELOG.md"

    /** The version key stored once the dialog is dismissed (name + code, so local builds differ too). */
    val versionKey: String get() = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"

    /** Sections to show now, or empty when the user already saw this version's notes or just installed. */
    suspend fun pending(context: Context, prefs: DsmPrefs): List<ChangelogSection> {
        val seen = prefs.whatsNewSeen.first()
        val isFirstRun = seen == null && isFreshInstall(context)
        if (isFirstRun) prefs.setWhatsNewSeen(versionKey)
        val isDue = seen != versionKey && !isFirstRun
        return if (isDue) load(context = context, seenVersion = seen?.substringBefore('+')) else emptyList()
    }

    /** The notes for this version after [seenVersion] (debug API `/whats_new?show=1` passes none). */
    suspend fun load(
        context: Context,
        seenVersion: String? = null,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): List<ChangelogSection> {
        val markdown =
            withContext(io) {
                val text =
                    runCatching {
                        context.assets.open(ASSET).use { stream -> stream.readBytes().decodeToString() }
                    }
                text.getOrDefault("")
            }
        return Changelog.forUpdate(markdown = markdown, seen = seenVersion, current = BuildConfig.VERSION_NAME)
    }

    private fun isFreshInstall(context: Context): Boolean {
        val info =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            }.getOrNull()
        return info != null && info.firstInstallTime == info.lastUpdateTime
    }
}
