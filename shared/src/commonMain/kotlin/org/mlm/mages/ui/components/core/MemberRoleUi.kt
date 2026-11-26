package org.mlm.mages.ui.components.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.MemberRole

@Composable
fun memberRoleLabel(role: MemberRole): String = when (role) {
    MemberRole.Creator -> stringResource(Res.string.owner)
    MemberRole.Owner -> stringResource(Res.string.owner)
    MemberRole.Admin -> stringResource(Res.string.admin)
    MemberRole.Moderator -> stringResource(Res.string.moderator)
    MemberRole.User -> stringResource(Res.string.user)
}

@Composable
fun memberRoleColor(role: MemberRole): Color = when (role) {
    MemberRole.Creator -> MaterialTheme.colorScheme.secondary
    MemberRole.Owner -> MaterialTheme.colorScheme.secondary
    MemberRole.Admin -> MaterialTheme.colorScheme.primary
    MemberRole.Moderator -> MaterialTheme.colorScheme.tertiary
    MemberRole.User -> MaterialTheme.colorScheme.onSurfaceVariant
}
