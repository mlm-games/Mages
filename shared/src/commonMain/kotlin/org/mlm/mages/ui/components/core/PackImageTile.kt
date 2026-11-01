package org.mlm.mages.ui.components.core

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.panpf.sketch.AsyncImage as SketchAsyncImage
import kotlinx.coroutines.delay
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.ui.components.sticker.TgsSticker

private const val PreviewPlayDelayMs = 120L

/**
 * One pack image. Animated entries stay on the still preview until the cell is
 * pressed, hovered or focused, so a full grid never runs more than one decoder.
 *
 * [resolveFull] fetches the animated original. It is only called while the cell
 * is active and only for entries that are not TGS, whose preview is already the
 * animation source.
 */
@Composable
fun PackImageTile(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    onClick: (() -> Unit)? = null,
    mime: String? = null,
    animated: Boolean = false,
    resolveFull: (suspend () -> String?)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val active = pressed || hovered || focused

    var play by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            play = false
            return@LaunchedEffect
        }
        delay(PreviewPlayDelayMs)
        play = true
    }

    val tgs = isTgsSticker(mime, path)
    var fullPath by remember(path) { mutableStateOf<String?>(null) }
    val resolve = rememberUpdatedState(resolveFull)
    LaunchedEffect(play, animated, path, mime) {
        if (!play || !animated || tgs || fullPath != null) return@LaunchedEffect
        fullPath = resolve.value?.invoke()
    }

    val base = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .let { tile ->
            if (onClick != null) {
                tile.clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                )
            } else {
                tile
            }
        }

    val animatedPath = fullPath.takeIf { play && animated }
    if (animatedPath != null) {
        SketchAsyncImage(
            uri = animatedPath,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = base,
        )
        return
    }

    if (path == null) {
        Box(base)
        return
    }

    if (tgs) {
        TgsSticker(
            source = path,
            contentDescription = contentDescription,
            animate = play && animated,
            contentScale = contentScale,
            modifier = base,
        )
        return
    }

    val context = LocalPlatformContext.current
    val model = remember(path) {
        ImageRequest.Builder(context).data(path).crossfade(true).build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = base
    )
}
