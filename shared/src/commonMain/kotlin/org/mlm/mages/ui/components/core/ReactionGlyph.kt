package org.mlm.mages.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.mlm.mages.settings.isImageReaction

@Composable
fun ReactionGlyph(
    key: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    resolvePreview: suspend (String) -> String? = { null },
) {
    if (!isImageReaction(key)) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(key, fontSize = (size.value * 0.5f).sp, maxLines = 1)
        }
        return
    }

    var path by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) { path = resolvePreview(key) }

    val resolved = path
    if (resolved == null) {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.2f))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(resolved)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
