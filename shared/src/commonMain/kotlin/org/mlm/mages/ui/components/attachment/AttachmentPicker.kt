package org.mlm.mages.ui.components.attachment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.picker_camera
import mages.shared.generated.resources.picker_camera_sub
import mages.shared.generated.resources.picker_document
import mages.shared.generated.resources.picker_document_sub
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
import mages.shared.generated.resources.picker_sticker
import mages.shared.generated.resources.picker_sticker_sub
import mages.shared.generated.resources.picker_video
import mages.shared.generated.resources.picker_video_sub

@Composable
fun AttachmentPicker(
    onPickImage: () -> Unit,
    onPickSticker: () -> Unit,
    onPickVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onPasteFromClipboard: (() -> Unit)?,
    onDismiss: () -> Unit,
    onCamera: (() -> Unit)? = null,
    onCreatePoll: (() -> Unit)? = null,
    onShareLocation: (() -> Unit)? = null,
    onShareStaticLocation: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            Text(
                stringResource(Res.string.picker_share),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            if (onPasteFromClipboard != null) {
                AttachmentOption(
                    Icons.Default.ContentPaste,
                    stringResource(Res.string.picker_paste),
                    stringResource(Res.string.picker_paste_sub)
                ) { onPasteFromClipboard(); onDismiss() }
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
            }

            // Media section
            AttachmentOption(
                Icons.Default.Image,
                stringResource(Res.string.picker_photo),
                stringResource(Res.string.picker_photo_sub)
            ) { onPickImage(); onDismiss() }

            AttachmentOption(
                Icons.Default.EmojiEmotions,
                stringResource(Res.string.picker_sticker),
                stringResource(Res.string.picker_sticker_sub)
            ) { onPickSticker(); onDismiss() }

            AttachmentOption(
                Icons.Default.VideoLibrary,
                stringResource(Res.string.picker_video),
                stringResource(Res.string.picker_video_sub)
            ) { onPickVideo(); onDismiss() }

            if (onCamera != null && currentPlatform == SettingPlatform.ANDROID) {
                AttachmentOption(
                    Icons.Default.PhotoCamera,
                    stringResource(Res.string.picker_camera),
                    stringResource(Res.string.picker_camera_sub)
                ) { onCamera(); onDismiss() }
            }

            AttachmentOption(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                stringResource(Res.string.picker_document),
                stringResource(Res.string.picker_document_sub)
            ) { onPickDocument(); onDismiss() }

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
                        Icons.Default.Poll,
                        stringResource(Res.string.picker_poll),
                        stringResource(Res.string.picker_poll_sub)
                    ) { onCreatePoll(); onDismiss() }
                }

                if ((onShareLocation != null || onShareStaticLocation != null) && currentPlatform == SettingPlatform.ANDROID) {
                    if (onShareStaticLocation != null) {
                        AttachmentOption(
                            Icons.Default.LocationOn,
                            stringResource(Res.string.picker_location),
                            stringResource(Res.string.picker_location_sub)
                        ) { onShareStaticLocation(); onDismiss() }
                    }
                    if (onShareLocation != null) {
                        AttachmentOption(
                            Icons.Default.Timeline,
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
    icon: ImageVector,
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
