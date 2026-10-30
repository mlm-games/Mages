package org.mlm.mages.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.ui.components.sticker.TgsSticker

@Composable
fun PackImageTile(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    onClick: (() -> Unit)? = null,
    mime: String? = null,
) {
    val base = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    if (path == null) {
        Box(base)
        return
    }

    if (isTgsSticker(mime, path)) {
        TgsSticker(
            source = path,
            contentDescription = contentDescription,
            animate = false,
            contentScale = contentScale,
            modifier = base,
        )
        return
    }

    val context = LocalPlatformContext.current
    val model = remember(path) { ImageRequest.Builder(context).data(path).crossfade(true).build() }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = base
    )
}
