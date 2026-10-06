package org.mlm.mages.ui.components.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.captionOr
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.downloadNameHint
import org.mlm.mages.ui.util.formatPinnedTimestamp

data class ImageViewerItem(
    val event: MessageEvent,
    val previewPath: String?,
)

fun MessageEvent.isViewableImage(): Boolean =
    attachment?.kind == AttachmentKind.Image || sticker != null

fun imageViewerItems(
    events: List<MessageEvent>,
    previewPathOf: (MessageEvent) -> String?,
): List<ImageViewerItem> = events
    .filter { it.isViewableImage() }
    .map { ImageViewerItem(it, previewPathOf(it)) }

suspend fun MatrixService.loadImageForViewing(event: MessageEvent): Result<String> {
    val attachment = event.attachment
    if (attachment != null) {
        return port.downloadAttachmentToCache(
            attachment,
            downloadNameHint(event, attachment.fileName, attachment.mime, "file"),
        )
    }
    val sticker = checkNotNull(event.sticker) { "viewer item without media" }
    return downloadStickerToCache(
        sticker,
        downloadNameHint(event, null, sticker.mime, "sticker"),
    )
}

@Composable
fun ImageViewerOverlay(
    items: List<ImageViewerItem>,
    startKey: String,
    startIndex: Int,
    onDismiss: () -> Unit,
    loadFullMedia: suspend (MessageEvent) -> Result<String>,
    onOpenMedia: (String, String?) -> Unit,
    onShareMedia: (String, String?) -> Unit,
    onLoadFailed: () -> Unit,
) {
    if (items.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        key(startKey) { ImageViewerPages(items, startIndex, onDismiss, loadFullMedia, onOpenMedia, onShareMedia, onLoadFailed) }
    }
}

@Composable
private fun ImageViewerPages(
    items: List<ImageViewerItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    loadFullMedia: suspend (MessageEvent) -> Result<String>,
    onOpenMedia: (String, String?) -> Unit,
    onShareMedia: (String, String?) -> Unit,
    onLoadFailed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val fullMedia = remember { mutableStateMapOf<String, String>() }
    val failed = remember { mutableSetOf<String>() }
    val loads = remember { mutableStateMapOf<String, Deferred<Result<String>>>() }
    var chromeVisible by remember { mutableStateOf(true) }

    fun loadFor(event: MessageEvent): Deferred<Result<String>> {
        val id = event.eventId
        loads[id]?.let { return it }
        return scope.async { loadFullMedia(event) }.also { loads[id] = it }
    }

    fun needsLoad(item: ImageViewerItem): Boolean {
        val id = item.event.eventId
        return fullMedia[id] == null && id !in failed
    }

    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            for (index in page - 1..page + 1) {
                val item = items.getOrNull(index) ?: continue
                if (!needsLoad(item)) continue
                val id = item.event.eventId
                launch {
                    val path = loadFor(item.event).await().getOrNull()
                    when {
                        path.isNullOrBlank() -> if (failed.add(id)) onLoadFailed()
                        else -> fullMedia[id] = path
                    }
                }
            }
        }
    }

    fun onCurrentMedia(action: (String, String?) -> Unit) {
        val item = items.getOrNull(pagerState.currentPage) ?: return
        val id = item.event.eventId
        scope.launch {
            val path = fullMedia[id] ?: loadFor(item.event).await().getOrNull()
            if (path.isNullOrBlank()) {
                if (failed.add(id)) onLoadFailed()
            } else {
                fullMedia[id] = path
                action(path, item.event.mediaMime())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { items[it].event.itemId },
        ) { page ->
            val item = items[page]
            ZoomableImage(
                model = fullMedia[item.event.eventId] ?: item.previewPath,
                contentDescription = item.event.attachment?.fileName,
                onTap = { chromeVisible = !chromeVisible },
                onDraggingChange = { dragging -> chromeVisible = !dragging },
                onDismiss = onDismiss,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val current = items.getOrNull(pagerState.currentPage)
            ImageViewerChrome(
                senderName = current?.event?.senderDisplayName ?: current?.event?.sender,
                timestampMs = current?.event?.timestampMs,
                caption = current?.event?.viewerCaption(),
                page = pagerState.currentPage,
                pageCount = items.size,
                onClose = onDismiss,
                onShare = { onCurrentMedia(onShareMedia) },
                onOpen = { onCurrentMedia(onOpenMedia) },
            )
        }
    }
}

private fun MessageEvent.mediaMime(): String? = attachment?.mime ?: sticker?.mime

private fun MessageEvent.viewerCaption(): String? {
    val attachment = attachment
    return when {
        attachment != null -> attachment.captionOr(body)
        else -> body.takeIf { it.isNotBlank() && !it.startsWith("mxc://") }
    }
}

@Composable
private fun ImageViewerChrome(
    senderName: String?,
    timestampMs: Long?,
    caption: String?,
    page: Int,
    pageCount: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.back),
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = senderName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (timestampMs != null) {
                    Text(
                        text = formatPinnedTimestamp(timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(Res.string.share),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(Res.string.open_in),
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pageCount > 1) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "${page + 1} / $pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}