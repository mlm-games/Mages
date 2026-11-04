package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun MemberListSheet(
    members: List<MemberSummary>,
    bannedMembers: List<MemberSummary>,
    isLoading: Boolean,
    myUserId: String?,
    activeCallParticipants: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onMemberClick: (MemberSummary) -> Unit,
    onInvite: () -> Unit
) {
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
                    stringResource(Res.string.members_count, members.size),
                    style = MaterialTheme.typography.titleMedium
                )
                FilledTonalButton(onClick = onInvite) {
                    Icon(painterResource(Res.drawable.ic_person_add), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.invite))
                }
            }
            
            HorizontalDivider()
            
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.sm)
                ) {
                    items(members, key = { it.userId }) { member ->
                        MemberListItem(
                            member = member,
                            isMe = member.userId == myUserId,
                            isInCall = member.userId in activeCallParticipants,
                            onClick = { onMemberClick(member) }
                        )
                    }
                    if (bannedMembers.isNotEmpty()) {
                        item(key = "banned_header") {
                            Text(
                                stringResource(Res.string.banned_count, bannedMembers.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.lg,
                                    vertical = Spacing.sm
                                )
                            )
                        }
                        items(bannedMembers, key = { "banned_${it.userId}" }) { member ->
                            MemberListItem(
                                member = member,
                                isMe = member.userId == myUserId,
                                isInCall = false,
                                onClick = { onMemberClick(member) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberListItem(
    member: MemberSummary,
    isMe: Boolean,
    isInCall: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName ?: member.userId,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                )
                if (isMe) {
                    Spacer(Modifier.width(Spacing.sm))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            stringResource(Res.string.you),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        supportingContent = {
            Text(
                member.userId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Avatar(
                name = member.displayName ?: member.userId,
                avatarPath = member.avatarUrl,
                size = Sizes.avatarSmall
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isInCall) {
                    InCallBadge()
                }
                MembershipBadge(member.membership)
            }
        },
        modifier = Modifier.clickable(enabled = !isMe) { onClick() }
    )
}

@Composable
private fun InCallBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                painterResource(Res.drawable.ic_call),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(Res.string.in_call),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MembershipBadge(membership: String) {
    val (color, text) = when (membership.lowercase()) {
        "join" -> MaterialTheme.colorScheme.primary to stringResource(Res.string.member)
        "invite" -> MaterialTheme.colorScheme.tertiary to stringResource(Res.string.invited)
        "ban" -> MaterialTheme.colorScheme.error to stringResource(Res.string.banned)
        "leave" -> MaterialTheme.colorScheme.outline to stringResource(Res.string.left)
        else -> MaterialTheme.colorScheme.outline to membership
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}