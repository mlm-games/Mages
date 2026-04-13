package org.mlm.mages.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    icon: ImageVector? = null,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                if (isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(cancelText)
            }
        }
    )
}
