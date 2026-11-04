package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.toTransferItem
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.components.wallpaper.WallpaperBackground
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.wallpaper.WallpaperConfig
import org.mlm.mages.wallpaper.WallpaperStore

/** Above this the upload is refused rather than read into memory and sent. */
private const val MAX_WALLPAPER_BYTES = 8L * 1024 * 1024

@Composable
fun WallpaperRoute(onBack: () -> Unit, roomId: String? = null) {
    val service: MatrixService = koinInject()
    val store: WallpaperStore = koinInject()
    val global by store.wallpaper.collectAsState()
    val roomWallpapers by store.roomWallpapers.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarManager: SnackbarManager = koinInject()
    var uploading by remember { mutableStateOf(false) }

    val room = roomId
    val override = room?.let { roomWallpapers[it] }
    val wallpaper = override ?: global

    LaunchedEffect(Unit) {
        store.refresh()
    }

    val picker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            // Checked before the file is read: staging bytes for an oversized
            // image would put it in memory first, on web most of all.
            if (runCatching { file.size() }.getOrDefault(0L) > MAX_WALLPAPER_BYTES) {
                snackbarManager.showError(getString(Res.string.wallpaper_image_too_large))
                return@launch
            }
            val client = service.portOrNull
            if (client == null) {
                snackbarManager.showError(getString(Res.string.wallpaper_upload_failed))
                return@launch
            }
            uploading = true
            try {
                val item = file.toTransferItem()
                val path = item.path
                val mime = item.mimeType ?: file.mimeType()?.toString()
                if (path.isNullOrBlank() || mime == null || !mime.startsWith("image/")) {
                    snackbarManager.showError(getString(Res.string.wallpaper_pick_image_only))
                    return@launch
                }
                client.uploadImageFromPath(path, mime)
                    .mapCatching { mxc -> store.setImage(mxc, room).getOrThrow() }
                    .onFailure { error ->
                        snackbarManager.showError(
                            error.message ?: getString(Res.string.wallpaper_upload_failed)
                        )
                    }
            } finally {
                uploading = false
            }
        }
    }

    WallpaperScreen(
        wallpaper = wallpaper,
        isUploading = uploading,
        inheritsGlobal = room != null && override == null,
        onBack = onBack,
        onChooseImage = { picker.launch() },
        onOpacityChange = { value ->
            scope.launch { store.setOpacity(value, room).onFailure { snackbarManager.reportWallpaperFailure(it) } }
        },
        onBlurChange = { value ->
            scope.launch { store.setBlur(value, room).onFailure { snackbarManager.reportWallpaperFailure(it) } }
        },
        onRemove = if (room == null) {
            { scope.launch { store.remove().onFailure { snackbarManager.reportWallpaperFailure(it) } } }
        } else {
            null
        },
        onSetNoWallpaper = if (room != null) {
            { scope.launch { store.disableForRoom(room).onFailure { snackbarManager.reportWallpaperFailure(it) } } }
        } else {
            null
        },
        onResetToGlobal = if (room != null && override != null) {
            { scope.launch { store.remove(room).onFailure { snackbarManager.reportWallpaperFailure(it) } } }
        } else {
            null
        },
    )
}

private suspend fun SnackbarManager.reportWallpaperFailure(error: Throwable) {
    showError(error.message ?: getString(Res.string.wallpaper_upload_failed))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(
    wallpaper: WallpaperConfig?,
    isUploading: Boolean,
    inheritsGlobal: Boolean = false,
    onBack: () -> Unit,
    onChooseImage: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBlurChange: (Float) -> Unit,
    onRemove: (() -> Unit)?,
    onSetNoWallpaper: (() -> Unit)? = null,
    onResetToGlobal: (() -> Unit)? = null,
) {
    val snackbarManager: SnackbarManager = koinInject()
    val hasWallpaper = wallpaper?.url != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.setting_chat_wallpaper), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        },
        snackbarHost = { snackbarManager.snackbarHost() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasWallpaper) {
                        WallpaperBackground(config = wallpaper)
                    } else {
                        Text(
                            text = stringResource(Res.string.wallpaper_none_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isUploading) {
                        LoadingIndicator()
                    }
                }
            }

            if (!hasWallpaper) {
                Text(
                    text = stringResource(Res.string.wallpaper_none_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (inheritsGlobal && hasWallpaper) {
                Text(
                    text = stringResource(Res.string.wallpaper_using_global),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onChooseImage,
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (hasWallpaper) Res.string.wallpaper_change_image else Res.string.wallpaper_choose_image
                    )
                )
            }

            if (hasWallpaper && wallpaper != null) {
                SliderRow(
                    label = stringResource(Res.string.wallpaper_opacity),
                    value = wallpaper.opacity,
                    valueRange = WallpaperConfig.MIN_OPACITY..WallpaperConfig.MAX_OPACITY,
                    onChange = onOpacityChange,
                )
                SliderRow(
                    label = stringResource(Res.string.wallpaper_blur),
                    value = wallpaper.blur,
                    valueRange = WallpaperConfig.MIN_BLUR..WallpaperConfig.MAX_BLUR,
                    onChange = onBlurChange,
                )
            }

            if (onSetNoWallpaper != null && hasWallpaper) {
                OutlinedButton(
                    onClick = onSetNoWallpaper,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.wallpaper_room_none))
                }
            }

            if (onResetToGlobal != null) {
                OutlinedButton(
                    onClick = onResetToGlobal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.wallpaper_reset_to_global))
                }
            }

            if (onRemove != null && hasWallpaper) {
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(painterResource(Res.drawable.ic_delete), contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.wallpaper_remove))
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    // Dragging updates the preview locally; the account data write happens once
    // the finger lifts, so a drag is not a burst of requests.
    var dragging by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    val shown = if (isDragging) dragging else value

    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Slider(
                value = shown,
                onValueChange = {
                    dragging = it
                    isDragging = true
                },
                onValueChangeFinished = {
                    isDragging = false
                    onChange(dragging)
                },
                valueRange = valueRange,
            )
        }
    )
}
