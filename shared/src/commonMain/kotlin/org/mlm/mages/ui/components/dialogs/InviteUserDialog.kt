package org.mlm.mages.ui.components.dialogs

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.MEMBER_SUGGESTION_LIMIT
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.isMatrixUserId
import org.mlm.mages.matrix.memberSuggestions
import org.mlm.mages.ui.components.common.MemberSuggestionGroup
import org.mlm.mages.ui.components.common.MemberSuggestionList
import org.mlm.mages.ui.components.common.rememberDirectoryMembers
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun InviteUserDialog(
    onInvite: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    suggestions: List<MemberSummary> = emptyList(),
    suggestionsLabel: String? = null,
) {
    var userId by remember { mutableStateOf("") }
    val localMatches = remember(suggestions, userId) {
        suggestions.memberSuggestions(userId)
    }
    val directoryBudget = (MEMBER_SUGGESTION_LIMIT - localMatches.size).coerceAtLeast(0)
    val directoryMatches = rememberDirectoryMembers(
        query = userId,
        enabled = directoryBudget > 0,
        limit = directoryBudget,
    ).filter { candidate -> localMatches.none { it.userId == candidate.userId } }

    val localLabel = suggestionsLabel ?: stringResource(Res.string.space_members_of_any)
    val directoryLabel = stringResource(Res.string.people_on_your_server)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(painterResource(Res.drawable.ic_person_add), null)
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
                MemberSuggestionList(
                    groups = listOf(
                        MemberSuggestionGroup(label = localLabel, members = localMatches),
                        MemberSuggestionGroup(label = directoryLabel, members = directoryMatches),
                    ),
                    onPick = { userId = it.userId },
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(userId.trim()) },
                enabled = isMatrixUserId(userId) && !isLoading
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
