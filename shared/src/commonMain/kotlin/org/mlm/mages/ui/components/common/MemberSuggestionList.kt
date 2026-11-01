package org.mlm.mages.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MEMBER_SUGGESTION_LIMIT
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.memberSuggestions
import org.mlm.mages.matrix.toMemberSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.rememberResolvedAvatar
import org.mlm.mages.ui.theme.Spacing

private const val DIRECTORY_SEARCH_DEBOUNCE_MS = 300L
private const val DIRECTORY_QUERY_MIN_LENGTH = 2

data class MemberSuggestionGroup(
    val label: String,
    val members: List<MemberSummary>,
)

@Composable
fun MemberSuggestionList(
    groups: List<MemberSuggestionGroup>,
    onPick: (MemberSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = groups.filter { it.members.isNotEmpty() }
    if (visible.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            visible.forEach { group ->
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Spacing.md,
                        vertical = Spacing.xs
                    )
                )
                group.members.forEach { member ->
                    MemberSuggestionRow(member = member, onPick = onPick)
                }
            }
        }
    }
}

@Composable
fun rememberDirectoryMembers(
    query: String,
    enabled: Boolean = true,
    limit: Int = MEMBER_SUGGESTION_LIMIT,
): List<MemberSummary> {
    val service: MatrixService = koinInject()
    var matches by remember(query) { mutableStateOf<List<MemberSummary>>(emptyList()) }

    LaunchedEffect(query, enabled, limit) {
        matches = emptyList()
        val needle = query.trim().removePrefix("@").trim()
        if (!enabled || needle.length < DIRECTORY_QUERY_MIN_LENGTH || needle.contains(":")) {
            return@LaunchedEffect
        }
        delay(DIRECTORY_SEARCH_DEBOUNCE_MS)
        matches = try {
            service.port.searchUsers(needle, limit * 4)
                .map { it.toMemberSummary() }
                .memberSuggestions(query, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    return matches
}

@Composable
private fun MemberSuggestionAvatar(member: MemberSummary) {
    Avatar(
        name = member.displayName ?: member.userId,
        avatarPath = rememberResolvedAvatar(member.avatarUrl, px = 32),
        size = 28.dp,
    )
}

@Composable
private fun MemberSuggestionRow(
    member: MemberSummary,
    onPick: (MemberSummary) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(member) }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        MemberSuggestionAvatar(member = member)
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName ?: member.userId,
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
