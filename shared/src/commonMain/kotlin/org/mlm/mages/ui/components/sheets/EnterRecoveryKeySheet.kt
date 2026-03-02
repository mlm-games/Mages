package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.create_new_recovery_key
import mages.shared.generated.resources.recover
import mages.shared.generated.resources.recovery_key
import mages.shared.generated.resources.recovery_key_hint
import mages.shared.generated.resources.recovery_key_prompt_body
import mages.shared.generated.resources.recovery_key_prompt_title
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SecurityViewModel

@Composable
fun EnterRecoveryKeySheet(
    viewModel: SecurityViewModel,
    onResetRecovery: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val isSubmitting = state.isSubmittingRecoveryKey
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(300) // wait for sheet anim
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )

        Text(
            stringResource(Res.string.recovery_key_prompt_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            stringResource(Res.string.recovery_key_prompt_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = state.recoveryKeyInput,
            onValueChange = viewModel::setRecoveryKey,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text(stringResource(Res.string.recovery_key)) },
            placeholder = { Text(stringResource(Res.string.recovery_key_hint)) },
            shape = MaterialTheme.shapes.medium,
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            enabled = !isSubmitting,
            isError = state.error != null,
            supportingText = {
                if (state.error != null) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (state.recoveryKeyInput.isNotBlank()) viewModel.submitRecoveryKey()
                }
            )
        )

        Button(
            onClick = viewModel::submitRecoveryKey,
            enabled = state.recoveryKeyInput.isNotBlank() && !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(stringResource(Res.string.recover))
        }

        TextButton(
            onClick = onResetRecovery,
            enabled = !isSubmitting,
        ) {
            Text(stringResource(Res.string.create_new_recovery_key))
        }
    }
}