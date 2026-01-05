package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.accounts.MatrixAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<MatrixAccount>,
    activeAccountId: String?,
    onSelectAccount: (MatrixAccount) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (MatrixAccount) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Accounts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            LazyColumn {
                items(accounts, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        isActive = account.id == activeAccountId,
                        onClick = {
                            onSelectAccount(account)
                            onDismiss()
                        },
                        onRemove = { onRemoveAccount(account) }
                    )
                }

                item {
                    ListItem(
                        headlineContent = { Text("Add account") },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onAddAccount()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: MatrixAccount,
    isActive: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                account.displayName ?: account.userId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (account.displayName != null) {
                Text(
                    account.userId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showRemoveDialog = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "LogOut",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.clickable(enabled = !isActive) { onClick() }
    )

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove account?") },
            text = { Text("This will log out ${account.userId} from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}