package org.mlm.mages.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.cancel
import mages.shared.generated.resources.close
import mages.shared.generated.resources.qr_grant_approved
import mages.shared.generated.resources.qr_grant_approve_title
import mages.shared.generated.resources.qr_grant_check_code
import mages.shared.generated.resources.qr_grant_done
import mages.shared.generated.resources.qr_grant_done_body
import mages.shared.generated.resources.qr_grant_failed
import mages.shared.generated.resources.qr_grant_open_browser
import mages.shared.generated.resources.qr_grant_syncing
import mages.shared.generated.resources.qr_grant_waiting
import mages.shared.generated.resources.qr_link_device
import mages.shared.generated.resources.qr_scan_again
import mages.shared.generated.resources.qr_scan_camera
import mages.shared.generated.resources.qr_scan_choose_image
import mages.shared.generated.resources.qr_scan_hint
import mages.shared.generated.resources.qr_scan_no_code
import mages.shared.generated.resources.qr_scan_title
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.platform.decodeQrImage
import org.mlm.mages.platform.rememberCameraPickerLauncher
import org.mlm.mages.ui.QrGrantPhase

@Composable
fun QrLinkDeviceDialog(
    phase: QrGrantPhase,
    checkCode: Int?,
    verificationUri: String?,
    error: String?,
    onQrDecoded: (ByteArray) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val noCodeMessage = stringResource(Res.string.qr_scan_no_code)
    var scanError by remember { mutableStateOf<String?>(null) }

    fun handleFile(file: PlatformFile?) {
        if (file == null) return
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            val qr = bytes?.let { runCatching { decodeQrImage(it) }.getOrNull() }
            if (qr == null) {
                scanError = noCodeMessage
            } else {
                scanError = null
                onQrDecoded(qr)
            }
        }
    }

    val imagePicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        handleFile(file)
    }
    val cameraPicker = rememberCameraPickerLauncher { file ->
        handleFile(file)
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (phase) {
                    QrGrantPhase.Scanning -> {
                        Text(
                            text = stringResource(Res.string.qr_scan_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(Res.string.qr_scan_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (cameraPicker != null) {
                            Button(
                                onClick = cameraPicker::launch,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, null)
                                Text(
                                    text = stringResource(Res.string.qr_scan_camera),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = imagePicker::launch,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(Res.string.qr_scan_choose_image))
                        }
                        scanError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    QrGrantPhase.Starting -> {
                        Text(
                            text = stringResource(Res.string.qr_link_device),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            text = stringResource(Res.string.qr_grant_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    QrGrantPhase.CheckCode -> {
                        Text(
                            text = stringResource(Res.string.qr_grant_check_code, (checkCode ?: 0).toString().padStart(2, '0')),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            text = stringResource(Res.string.qr_grant_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    QrGrantPhase.WaitingForAuth -> {
                        Text(
                            text = stringResource(Res.string.qr_grant_approve_title),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        verificationUri?.let { uri ->
                            Button(
                                onClick = { runCatching { uriHandler.openUri(uri) } },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(stringResource(Res.string.qr_grant_open_browser))
                            }
                        }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(Res.string.qr_grant_approved))
                        }
                    }

                    QrGrantPhase.Confirming, QrGrantPhase.SyncingSecrets -> {
                        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            text = stringResource(
                                if (phase == QrGrantPhase.SyncingSecrets) Res.string.qr_grant_syncing
                                else Res.string.qr_grant_waiting
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrGrantPhase.Done -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Text(
                            text = stringResource(Res.string.qr_grant_done),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.qr_grant_done_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrGrantPhase.Error -> {
                        Text(
                            text = error ?: stringResource(Res.string.qr_grant_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(Res.string.qr_scan_again))
                        }
                    }

                    QrGrantPhase.Idle -> Unit
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(
                            stringResource(
                                if (phase == QrGrantPhase.Done) Res.string.close else Res.string.cancel
                            )
                        )
                    }
                }
            }
        }
    }
}
