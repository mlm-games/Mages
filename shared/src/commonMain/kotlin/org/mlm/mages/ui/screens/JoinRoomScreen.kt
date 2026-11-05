package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreviewMembership
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.dialogs.DeclineInviteDialog
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.JoinRoomUiState
import org.mlm.mages.ui.viewmodel.JoinRoomViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
fun JoinRoomScreen(
    viewModel: JoinRoomViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val isBusy = state.action != JoinRoomUiState.Action.Idle || state.isDecliningInvite

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            JoinRoomFooter(
                state = state,
                isBusy = isBusy,
                onJoin = viewModel::join,
                onKnock = viewModel::knock,
                onAccept = viewModel::acceptInvite,
                onDecline = viewModel::showDeclineDialog,
                onOpenJoined = viewModel::open,
                onDismissActionError = viewModel::dismissActionError,
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading -> LoadingIndicator()

                state.error != null -> EmptyState(
                    icon = painterResource(Res.drawable.ic_error),
                    title = stringResource(Res.string.failed_to_load_room_info),
                    subtitle = state.error,
                    action = {
                        Button(onClick = viewModel::load) {
                            Text(stringResource(Res.string.retry))
                        }
                    }
                )

                else -> JoinRoomContent(state)
            }
        }
    }

    if (state.showDeclineDialog) {
        DeclineInviteDialog(
            roomName = state.name ?: state.roomId.orEmpty(),
            inviterName = state.declineInviterName,
            isLoading = state.isDecliningInvite,
            onDecline = viewModel::declineInvite,
            onDismiss = viewModel::hideDeclineDialog,
        )
    }
}

@Composable
private fun JoinRoomContent(state: JoinRoomUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Spacer(Modifier.height(Spacing.md))

        Avatar(
            name = state.name?.takeIf { it.isNotBlank() } ?: state.roomId.orEmpty(),
            avatarPath = state.avatarPath,
            size = 120.dp,
            shape = CircleShape,
            modifier = Modifier.clip(CircleShape)
        )

        Text(
            text = state.name?.takeIf { it.isNotBlank() } ?: state.alias ?: state.roomId.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        state.alias?.let { alias ->
            val name = state.name?.takeIf { it.isNotBlank() }
            if (alias != name) {
                Text(
                    text = alias,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        state.memberCount?.let { count ->
            Text(
                text = stringResource(Res.string.members, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.heroes.isNotEmpty()) {
            HeroFacepile(state)
        }

        state.topic?.let { topic ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = topic,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )
        }
    }
}

@Composable
private fun HeroFacepile(state: JoinRoomUiState) {
    val heroes = state.heroes.take(6)
    Row(verticalAlignment = Alignment.CenterVertically) {
        heroes.forEachIndexed { index, hero ->
            Avatar(
                name = hero.displayName ?: hero.userId,
                avatarPath = hero.avatarUrl?.let { state.heroAvatarPaths[it] },
                size = 40.dp,
                modifier = Modifier
                    .offset(x = (-12 * index).dp)
                    .clip(CircleShape)
            )
        }
        if (state.heroes.size > heroes.size) {
            Text(
                text = "+${state.heroes.size - heroes.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun JoinRoomFooter(
    state: JoinRoomUiState,
    isBusy: Boolean,
    onJoin: () -> Unit,
    onKnock: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenJoined: () -> Unit,
    onDismissActionError: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            state.actionError?.let { error ->
                ActionErrorBanner(error, onDismissActionError)
            }

            when (state.membership) {
                RoomPreviewMembership.Joined -> {
                    Button(
                        onClick = onOpenJoined,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true,
                    ) {
                        Text(stringResource(Res.string.view))
                    }
                }

                RoomPreviewMembership.Knocked -> {
                    StatusNote(
                        icon = painterResource(Res.drawable.ic_mail),
                        text = stringResource(Res.string.knock_request_sent_waiting_for_approval),
                    )
                }

                RoomPreviewMembership.Banned -> {
                    StatusNote(
                        icon = painterResource(Res.drawable.ic_block),
                        text = stringResource(Res.string.you_are_banned_from_this_room),
                        isError = true,
                    )
                }

                RoomPreviewMembership.Invited -> {
                    Text(
                        text = stringResource(Res.string.you_are_invited_to_this_room),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        OutlinedButton(
                            onClick = onDecline,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.isDecliningInvite) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(Res.string.decline))
                            }
                        }
                        Button(
                            onClick = onAccept,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.action == JoinRoomUiState.Action.Accepting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(Res.string.accept))
                            }
                        }
                    }
                }

                else -> when (state.joinRule) {
                    RoomJoinRule.Invite -> {
                        StatusNote(
                            icon = painterResource(Res.drawable.ic_lock),
                            text = stringResource(Res.string.invite_only),
                        )
                    }

                    RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> {
                        Text(
                            text = stringResource(Res.string.knock_required_before_joining),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = onKnock,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.action == JoinRoomUiState.Action.Knocking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(Res.string.knock))
                            }
                        }
                    }

                    RoomJoinRule.Restricted -> {
                        StatusNote(
                            icon = painterResource(Res.drawable.ic_lock),
                            text = stringResource(Res.string.restricted_room_membership),
                        )
                        JoinButton(isBusy = isBusy, action = state.action, onJoin = onJoin)
                    }

                    RoomJoinRule.Public, null -> {
                        Text(
                            text = stringResource(Res.string.anyone_can_join),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        JoinButton(isBusy = isBusy, action = state.action, onJoin = onJoin)
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinButton(
    isBusy: Boolean,
    action: JoinRoomUiState.Action,
    onJoin: () -> Unit,
) {
    Button(
        onClick = onJoin,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (action == JoinRoomUiState.Action.Joining) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(Res.string.join))
        }
    }
}

@Composable
private fun StatusNote(
    icon: Painter,
    text: String,
    isError: Boolean = false,
) {
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            painterResource(Res.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painterResource(Res.drawable.ic_close),
                contentDescription = stringResource(Res.string.close),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
