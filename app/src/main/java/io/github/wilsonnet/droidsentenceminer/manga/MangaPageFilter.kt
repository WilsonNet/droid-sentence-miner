package io.github.wilsonnet.droidsentenceminer.manga

/**
 * How much the reader cleans up a page before drawing it ([PagePipeline]): levels (paper to white,
 * ink to black), a Lanczos resize to the exact screen size and contrast-adaptive sharpening.
 *
 * @property id persistent preference identifier
 * @property levels how far the paper/ink points are pulled (0..1)
 * @property gamma midtone curve; above 1 darkens thin, grey line art
 * @property sharpness CAS sharpness (0..1)
 */
enum class MangaPageFilter(val id: String, val levels: Float, val gamma: Float, val sharpness: Float) {
    /** The page as decoded, drawn with plain bilinear scaling. */
    OFF(id = "off", levels = 0f, gamma = 1f, sharpness = 0f),

    /** Light touch for clean digital releases. */
    SOFT(id = "soft", levels = 0.5f, gamma = 1f, sharpness = 0.3f),

    /** Default: white paper, solid ink, sharp lines. */
    CRISP(id = "crisp", levels = 1f, gamma = 1.1f, sharpness = 0.6f),

    /** For faint, blurry scans: heavier ink and sharpening. */
    STRONG(id = "strong", levels = 1f, gamma = 1.25f, sharpness = 0.9f),
    ;

    /** Pipeline strengths of this filter. */
    internal val settings: PagePipeline.Settings get() = PagePipeline.Settings(levels = levels, gamma = gamma, sharpness = sharpness)

    /** Lookup helpers. */
    companion object {
        /** Resolves [id], defaulting to [CRISP]. */
        fun fromId(id: String?): MangaPageFilter = entries.firstOrNull { it.id == id } ?: CRISP
    }
}
