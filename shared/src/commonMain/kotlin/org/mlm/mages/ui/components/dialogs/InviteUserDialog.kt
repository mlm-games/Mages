package org.mlm.mages.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

private const val SUGGESTION_LIMIT = 5

private fun MemberSummary.matchesQuery(query: String): Boolean {
    val needle = query.removePrefix("@").trim().lowercase()
    if (needle.isEmpty()) return false
    return userId.lowercase().contains(needle) ||
        displayName?.lowercase()?.contains(needle) == true
}

@Composable
fun InviteUserDialog(
    onInvite: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    suggestions: List<MemberSummary> = emptyList(),
    suggestionsSourceName: String? = null,
) {
    var userId by remember { mutableStateOf("") }
    val matches = remember(suggestions, userId) {
        if (userId.isBlank()) {
            emptyList()
        } else {
            suggestions
                .filter { it.userId != userId && it.matchesQuery(userId) }
                .take(SUGGESTION_LIMIT)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.PersonAdd, null)
        },
        title = {
            Text(stringResource(Res.string.invite_user))
        },
        text = {
            Column {
                Text(
                    stringResource(Res.string.enter_the_matrix_id_of_the_user_you_want_to_invite),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text(stringResource(Res.string.user_id)) },
                    placeholder = { Text(stringResource(Res.string.user_id_placeholder)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                            Text(
                                text = if (suggestionsSourceName != null) {
                                    stringResource(Res.string.space_members_of, suggestionsSourceName)
                                } else {
                                    stringResource(Res.string.space_members_of_any)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.md,
                                    vertical = Spacing.xs
                                )
                            )
                            matches.forEach { member ->
                                SpaceMemberRow(member = member, onPick = { userId = it.userId })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(userId.trim()) },
                enabled = userId.isNotBlank() && userId.contains("@") && userId.contains(":") && !isLoading
            ) {
                if (isLoading) {
                    LoadingIndicator(
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(Res.string.invite))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
private fun SpaceMemberAvatar(member: MemberSummary) {
    val service: MatrixService = koinInject()
    var avatarPath by remember(member.avatarUrl) {
        mutableStateOf(member.avatarUrl?.takeIf { !it.startsWith("mxc://") })
    }

    LaunchedEffect(member.avatarUrl) {
        val source = member.avatarUrl?.takeIf { it.startsWith("mxc://") } ?: return@LaunchedEffect
        avatarPath = runCatching { service.avatars.resolve(source, px = 32) }.getOrNull()
    }

    Avatar(
        name = member.displayName ?: member.userId,
        avatarPath = avatarPath,
        size = 28.dp,
    )
}

@Composable
private fun SpaceMemberRow(
    member: MemberSummary,
    onPick: (MemberSummary) -> Unit,
) {
    val name = member.displayName ?: member.userId
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(member) }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        SpaceMemberAvatar(member = member)
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = member.userId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
