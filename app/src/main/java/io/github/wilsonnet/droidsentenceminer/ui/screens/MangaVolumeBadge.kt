package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.manga.MangaPreOcrQueue

private const val PILL_PERCENT = 50
private const val BADGE_ALPHA = 0.9f

/**
 * Cover badge for states worth noticing (a paired .mokuro, a running full-volume OCR); the default
 * (OCR on demand) stays off the cover art.
 */
@Suppress("FunctionName")
@Composable
internal fun MangaVolumeBadge(
    isMokuro: Boolean,
    preOcr: MangaPreOcrQueue.VolumeProgress?,
    modifier: Modifier = Modifier,
) {
    val isBatchRunning = preOcr != null && preOcr.isRunning
    val badgeText =
        when {
            isBatchRunning -> "OCR ${preOcr.done}/${preOcr.total}"
            isMokuro -> "Mokuro"
            else -> null
        }
    if (badgeText != null) {
        Surface(
            shape = RoundedCornerShape(PILL_PERCENT),
            color = MaterialTheme.colorScheme.primary.copy(alpha = BADGE_ALPHA),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier.padding(8.dp),
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
