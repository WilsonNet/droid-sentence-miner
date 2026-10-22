package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Widest a column of cards and text gets before lines become hard to follow (tablets, landscape). */
val READABLE_WIDTH = 840.dp

/** Centers [content] in a column at most [READABLE_WIDTH] wide; it fills the screen when narrower. */
@Composable
fun ReadableWidth(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = READABLE_WIDTH))
    }
}
