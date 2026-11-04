package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceAddSheet(
    canManageChildren: Boolean,
    spaceChildReason: String?,
    canInvite: Boolean,
    inviteReason: String?,
    onCreateRoom: () -> Unit,
    onAddRoom: () -> Unit,
    onInvite: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                stringResource(Res.string.actions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.sm)
            )

            ActionListItem(
                icon = painterResource(Res.drawable.ic_add_comment),
                label = stringResource(Res.string.new_room_in_this_space),
                description = if (canManageChildren) stringResource(Res.string.create_a_room_inside_this_space)
                else spaceChildReason,
                enabled = canManageChildren,
                onClick = { onDismiss(); onCreateRoom() }
            )

            ActionListItem(
                icon = painterResource(Res.drawable.ic_add),
                label = stringResource(Res.string.add_rooms),
                description = if (canManageChildren) stringResource(Res.string.add_existing_rooms_to_this_space)
                else spaceChildReason,
                enabled = canManageChildren,
                onClick = { onDismiss(); onAddRoom() }
            )

            ActionListItem(
                icon = painterResource(Res.drawable.ic_person_add),
                label = stringResource(Res.string.invite_users),
                description = if (canInvite) stringResource(Res.string.invite_users_to_this_space)
                else inviteReason,
                enabled = canInvite,
                onClick = { onDismiss(); onInvite() }
            )

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
