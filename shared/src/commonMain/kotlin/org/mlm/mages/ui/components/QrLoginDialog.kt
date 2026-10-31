package org.mlm.mages.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.cancel
import mages.shared.generated.resources.qr_login_code_label
import mages.shared.generated.resources.qr_login_enter_code
import mages.shared.generated.resources.qr_login_instruction
import mages.shared.generated.resources.qr_login_link_device
import mages.shared.generated.resources.qr_login_scanned_title
import mages.shared.generated.resources.qr_login_syncing
import mages.shared.generated.resources.qr_login_title
import mages.shared.generated.resources.qr_login_user_code
import mages.shared.generated.resources.qr_login_waiting
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.LoginUiState
import org.mlm.mages.ui.QrLoginPhase

@Composable
fun QrLoginDialog(
    state: LoginUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
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
                Text(
                    text = stringResource(Res.string.qr_login_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )

                when (state.qrPhase) {
                    QrLoginPhase.Starting, QrLoginPhase.Confirming -> {
                        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            text = stringResource(Res.string.qr_login_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrLoginPhase.Ready -> {
                        state.qrMatrix?.let { matrix ->
                            QrCodeImage(
                                matrix = matrix,
                                contentDescription = stringResource(Res.string.qr_login_title),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = stringResource(Res.string.qr_login_instruction),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrLoginPhase.Scanned -> {
                        Text(
                            text = stringResource(Res.string.qr_login_scanned_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.qr_login_enter_code),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedTextField(
                            value = state.qrCheckCode,
                            onValueChange = onCodeChange,
                            label = { Text(stringResource(Res.string.qr_login_code_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = onSubmit,
                            enabled = state.qrCheckCode.length == 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(stringResource(Res.string.qr_login_link_device))
                        }
                    }

                    QrLoginPhase.WaitingForToken -> {
                        CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            text = stringResource(
                                Res.string.qr_login_user_code,
                                state.qrUserCode.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrLoginPhase.SyncingSecrets -> {
                        CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            text = stringResource(Res.string.qr_login_syncing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    QrLoginPhase.Idle -> Unit
                }

                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}
