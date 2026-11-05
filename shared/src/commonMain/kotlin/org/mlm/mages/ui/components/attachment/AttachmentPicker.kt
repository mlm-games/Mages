package org.mlm.mages.ui.components.attachment

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.picker_camera
import mages.shared.generated.resources.picker_camera_sub
import mages.shared.generated.resources.picker_file
import mages.shared.generated.resources.picker_file_sub
import mages.shared.generated.resources.picker_image_sticker
import mages.shared.generated.resources.picker_image_sticker_sub
import mages.shared.generated.resources.picker_interactive
import mages.shared.generated.resources.picker_live_location
import mages.shared.generated.resources.picker_live_location_sub
import mages.shared.generated.resources.picker_location
import mages.shared.generated.resources.picker_location_sub
import mages.shared.generated.resources.picker_paste
import mages.shared.generated.resources.picker_paste_sub
import mages.shared.generated.resources.picker_photo
import mages.shared.generated.resources.picker_photo_sub
import mages.shared.generated.resources.picker_poll
import mages.shared.generated.resources.picker_poll_sub
import mages.shared.generated.resources.picker_share
import mages.shared.generated.resources.picker_stickers
import mages.shared.generated.resources.picker_stickers_sub
import mages.shared.generated.resources.picker_video
import mages.shared.generated.resources.picker_video_sub

@Composable
fun AttachmentPicker(
    onPickImage: () -> Unit,
    onPickSticker: () -> Unit,
    onPickStickerPack: (() -> Unit)? = null,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onPasteFromClipboard: (() -> Unit)?,
    onDismiss: () -> Unit,
    onCamera: (() -> Unit)? = null,
    onCreatePoll: (() -> Unit)? = null,
    onShareLocation: (() -> Unit)? = null,
    onShareStaticLocation: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl)
        ) {
            Text(
                stringResource(Res.string.picker_share),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            if (onPasteFromClipboard != null) {
                AttachmentOption(
                    painterResource(Res.drawable.ic_content_paste),
                    stringResource(Res.string.picker_paste),
                    stringResource(Res.string.picker_paste_sub)
                ) { onPasteFromClipboard(); onDismiss() }
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
            }

            // Media section
            AttachmentOption(
                painterResource(Res.drawable.ic_image),
                stringResource(Res.string.picker_photo),
                stringResource(Res.string.picker_photo_sub)
            ) { onPickImage(); onDismiss() }

            if (onPickStickerPack != null) {
                AttachmentOption(
                    painterResource(Res.drawable.ic_collections_bookmark),
                    stringResource(Res.string.picker_stickers),
                    stringResource(Res.string.picker_stickers_sub)
                ) { onPickStickerPack(); onDismiss() }
            }

            AttachmentOption(
                painterResource(Res.drawable.ic_sentiment_satisfied),
                stringResource(Res.string.picker_image_sticker),
                stringResource(Res.string.picker_image_sticker_sub)
            ) { onPickSticker(); onDismiss() }

            AttachmentOption(
                painterResource(Res.drawable.ic_video_library),
                stringResource(Res.string.picker_video),
                stringResource(Res.string.picker_video_sub)
            ) { onPickVideo(); onDismiss() }

            if (onCamera != null && currentPlatform == SettingPlatform.ANDROID) {
                AttachmentOption(
                    painterResource(Res.drawable.ic_photo_camera),
                    stringResource(Res.string.picker_camera),
                    stringResource(Res.string.picker_camera_sub)
                ) { onCamera(); onDismiss() }
            }

            AttachmentOption(
                painterResource(Res.drawable.ic_description),
                stringResource(Res.string.picker_file),
                stringResource(Res.string.picker_file_sub)
            ) { onPickFile(); onDismiss() }

            // Interactive content
            if (onCreatePoll != null || onShareLocation != null) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

                Text(
                    stringResource(Res.string.picker_interactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )

                if (onCreatePoll != null) {
                    AttachmentOption(
                        painterResource(Res.drawable.ic_bar_chart),
                        stringResource(Res.string.picker_poll),
                        stringResource(Res.string.picker_poll_sub)
                    ) { onCreatePoll(); onDismiss() }
                }

                if (
                    (onShareLocation != null || onShareStaticLocation != null) &&
                    (
                        currentPlatform == SettingPlatform.ANDROID ||
                            currentPlatform == SettingPlatform.WEB ||
                            currentPlatform == SettingPlatform.JVM
                        )
                ) {
                    if (
                        onShareStaticLocation != null &&
                        (currentPlatform == SettingPlatform.ANDROID || currentPlatform == SettingPlatform.JVM)
                    ) {
                        AttachmentOption(
                            painterResource(Res.drawable.ic_location_on),
                            stringResource(Res.string.picker_location),
                            stringResource(Res.string.picker_location_sub)
                        ) { onShareStaticLocation(); onDismiss() }
                    }
                    if (onShareLocation != null) {
                        AttachmentOption(
                            painterResource(Res.drawable.ic_timeline),
                            stringResource(Res.string.picker_live_location),
                            stringResource(Res.string.picker_live_location_sub)
                        ) { onShareLocation(); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: Painter,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Box(Modifier.padding(Spacing.md)) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}
