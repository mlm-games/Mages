package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.KnockRequestSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun KnockRequestsSheet(
    requests: List<KnockRequestSummary>,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String, String?) -> Unit,
) {
    var declineTarget by remember { mutableStateOf<KnockRequestSummary?>(null) }
    var declineReason by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(Res.string.knock_requests_count, requests.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalDivider()

            if (requests.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(Res.string.no_pending_knock_requests),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(requests, key = { it.eventId }) { request ->
                        ListItem(
                            headlineContent = {
                                Text(request.displayName ?: request.userId)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        request.userId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    request.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                                        Spacer(Modifier.height(Spacing.xs))
                                        Text(reason, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            leadingContent = {
                                Avatar(
                                    name = request.displayName ?: request.userId,
                                    avatarPath = request.avatarUrl,
                                    size = Sizes.avatarSmall
                                )
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    OutlinedButton(onClick = {
                                        declineTarget = request
                                        declineReason = ""
                                    }) {
                                        Icon(painterResource(Res.drawable.ic_close), contentDescription = null)
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(stringResource(Res.string.decline))
                                    }
                                    Button(onClick = { onAccept(request.userId) }) {
                                        Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(stringResource(Res.string.accept))
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    declineTarget?.let { request ->
        DeclineKnockDialog(
            title = stringResource(Res.string.decline_user_named, request.displayName ?: request.userId),
            message = stringResource(Res.string.their_request_to_join_this_room_will_be_declined),
            reasonValue = declineReason,
            onReasonChange = { declineReason = it },
            onConfirm = {
                onDecline(request.userId, declineReason.ifBlank { null })
                declineTarget = null
            },
            onDismiss = { declineTarget = null }
        )
    }
}

@Composable
private fun DeclineKnockDialog(
    title: String,
    message: String,
    reasonValue: String,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(message)
                OutlinedTextField(
                    value = reasonValue,
                    onValueChange = onReasonChange,
                    label = { Text(stringResource(Res.string.reason_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(Res.string.decline))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
