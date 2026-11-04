package org.mlm.mages.ui.components.dialogs

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun RecoveryDialog(keyValue: String, onChange: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(Sizes.avatarMedium)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_key), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        title = { Text(stringResource(Res.string.enter_recovery_key), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(stringResource(Res.string.paste_your_recovery_key_to_restore_end_to_end_encryption_and_verify_this_session), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = keyValue, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(Res.string.enter_recovery_key)) }, supportingText = { Text(stringResource(Res.string.recovery_keys_are_usually_48_characters_in_groups_of_4)) }, shape = MaterialTheme.shapes.medium, singleLine = false, minLines = 3)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = keyValue.isNotBlank()) { Text(stringResource(Res.string.recover)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) } }
    )
}