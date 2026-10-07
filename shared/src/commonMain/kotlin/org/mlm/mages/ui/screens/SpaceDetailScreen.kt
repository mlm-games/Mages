package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.matrix.SpaceSection
import org.koin.compose.koinInject
import org.mlm.mages.ui.SpaceDetailUiState
import org.mlm.mages.ui.SpaceSectionEntry
import org.mlm.mages.ui.components.dialogs.AddRoomToSpaceDialog
import org.mlm.mages.ui.components.dialogs.CreateRoomInSpaceDialog
import org.mlm.mages.ui.components.dialogs.DeleteSectionDialog
import org.mlm.mages.ui.components.dialogs.InviteUserToSpaceDialog
import org.mlm.mages.ui.components.dialogs.MoveToSectionDialog
import org.mlm.mages.ui.components.dialogs.SpaceSectionNameDialog
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.LoadMoreButton
import org.mlm.mages.ui.components.core.SectionHeader
import org.mlm.mages.ui.components.sheets.SpaceAddSheet
import org.mlm.mages.ui.components.sheets.SpaceSectionsSheet
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SpaceActionsViewModel
import org.mlm.mages.ui.viewmodel.SpaceDetailViewModel
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

private const val DEFAULT_SECTION_KEY = "__default__"

@Composable
fun SpaceDetailScreen(
    viewModel: SpaceDetailViewModel,
    actionsViewModel: SpaceActionsViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val actionsState by actionsViewModel.state.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    var showAddSheet by remember { mutableStateOf(false) }
    var showSectionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let { postError(it) }
    }

    LaunchedEffect(Unit) {
        actionsViewModel.events.collect { event ->
            when (event) {
                is SpaceActionsViewModel.Event.ShowError -> postError(event.message)
                is SpaceActionsViewModel.Event.ShowSuccess -> snackbarManager.show(event.message)
                is SpaceActionsViewModel.Event.ChildAdded -> viewModel.refreshUntilRoomPresent(event.roomId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.space?.name ?: state.spaceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refresh()
                            actionsViewModel.refresh()
                        },
                        enabled = !state.isLoading
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.refresh))
                    }
                    IconButton(onClick = { showSectionsSheet = true }) {
                        Icon(Icons.Default.Tune, stringResource(Res.string.sections))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(Res.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            if (actionsState.hasAnyAction) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, stringResource(Res.string.add))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.add))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = state.isLoading && state.hierarchy.isEmpty()) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.space?.let { space ->
                SpaceHeaderCard(space = space, avatarPath = state.spaceAvatarPath)
            }

            when {
                state.isLoading && state.hierarchy.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                state.hierarchy.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(Res.string.this_space_is_empty),
                        subtitle = stringResource(Res.string.add_rooms_or_subspaces_to_organize_your_conversations)
                    )
                }

                else -> {
                    val visibleSections = state.sections.filter { section ->
                        !section.isEmpty || section.tag == null || section.bornIn == state.spaceId
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.lg)
                    ) {
                        visibleSections.forEach { section ->
                            item(key = "section_header_${section.tag ?: DEFAULT_SECTION_KEY}") {
                                val count = section.subspaces.size + section.rooms.size
                                SectionHeader(
                                    title = if (section.tag == null) {
                                        stringResource(Res.string.rooms_and_spaces)
                                    } else {
                                        section.name
                                    },
                                    count = count
                                )
                            }

                            items(section.subspaces, key = { "sub_${section.tag}_${it.roomId}" }) { child ->
                                SpaceChildRow(child = child, state = state, viewModel = viewModel)
                            }

                            if (section.subspaces.isNotEmpty() && section.rooms.isNotEmpty()) {
                                item(key = "rooms_${section.tag ?: DEFAULT_SECTION_KEY}") {
                                    SectionHeader(
                                        title = stringResource(Res.string.rooms),
                                        count = section.rooms.size
                                    )
                                }
                            }

                            items(section.rooms, key = { "room_${section.tag}_${it.roomId}" }) { child ->
                                SpaceChildRow(child = child, state = state, viewModel = viewModel)
                            }
                        }

                        if (state.nextBatch != null) {
                            item(key = "load_more") {
                                LoadMoreButton(
                                    isLoading = state.isLoadingMore,
                                    onClick = viewModel::loadMore
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        SpaceAddSheet(
            canManageChildren = actionsState.canManageChildren,
            spaceChildReason = actionsState.spaceChildReason,
            canInvite = actionsState.canInvite,
            inviteReason = actionsState.inviteReason,
            onCreateRoom = actionsViewModel::showCreateRoom,
            onAddRoom = {
                actionsViewModel.showAddRoom(state.hierarchy.mapTo(mutableSetOf()) { it.roomId })
            },
            onInvite = actionsViewModel::showInviteDialog,
            onDismiss = { showAddSheet = false }
        )
    }

    if (actionsState.showCreateRoom) {
        CreateRoomInSpaceDialog(
            name = actionsState.newRoomName,
            topic = actionsState.newRoomTopic,
            isPublic = actionsState.newRoomIsPublic,
            isSaving = actionsState.isSaving,
            onNameChange = actionsViewModel::setNewRoomName,
            onTopicChange = actionsViewModel::setNewRoomTopic,
            onPublicChange = actionsViewModel::setNewRoomIsPublic,
            onCreate = actionsViewModel::createRoomInSpace,
            onDismiss = actionsViewModel::hideCreateRoom
        )
    }

    if (actionsState.showAddRoom) {
        AddRoomToSpaceDialog(
            availableRooms = actionsState.addableRooms,
            isSaving = actionsState.isSaving,
            onAdd = { roomId, suggested -> actionsViewModel.addChild(roomId, suggested) },
            onDismiss = actionsViewModel::hideAddRoom
        )
    }

    if (actionsState.showInviteUser) {
        InviteUserToSpaceDialog(
            userId = actionsState.inviteUserId,
            onUserIdChange = actionsViewModel::setInviteUserId,
            onInvite = actionsViewModel::inviteUser,
            onDismiss = actionsViewModel::hideInviteDialog,
            isSaving = actionsState.isSaving
        )
    }

    var pendingDeletion by remember { mutableStateOf<SpaceSectionEntry?>(null) }

    if (showSectionsSheet) {
        SpaceSectionsSheet(
            sections = state.sections.filter { it.tag != null },
            isSaving = state.isSavingSection,
            onCreate = viewModel::showCreateSection,
            onRename = viewModel::showEditSection,
            onDelete = { pendingDeletion = it },
            onMove = viewModel::moveSection,
            onDismiss = { showSectionsSheet = false }
        )
    }

    state.editingSection?.let { editing ->
        SpaceSectionNameDialog(
            name = editing.name,
            isEditing = editing.tag != null,
            isSaving = state.isSavingSection,
            onNameChange = viewModel::setSectionName,
            onSave = viewModel::saveSection,
            onDelete = editing.tag?.let {
                { pendingDeletion = editing }
            },
            onDismiss = viewModel::hideSectionEditor
        )
    }

    pendingDeletion?.let { section ->
        DeleteSectionDialog(
            sectionName = section.name,
            onConfirm = {
                pendingDeletion = null
                viewModel.hideSectionEditor()
                section.tag?.let(viewModel::deleteSection)
            },
            onDismiss = { pendingDeletion = null }
        )
    }

    state.movingRoomId?.let { roomId ->
        MoveToSectionDialog(
            sections = state.sections.mapNotNull { entry ->
                entry.tag?.let { SpaceSection(it, entry.name, state.spaceId) }
            },
            currentTag = state.hierarchy.firstOrNull { it.roomId == roomId }?.sectionTag,
            onSelect = { tag -> viewModel.setRoomSection(roomId, tag) },
            onDismiss = viewModel::hideMoveToSection
        )
    }
}

@Composable
private fun SpaceChildRow(
    child: SpaceChildInfo,
    state: SpaceDetailUiState,
    viewModel: SpaceDetailViewModel
) {
    val resolvedAvatar = state.avatarPathByRoomId[child.roomId] ?: child.avatarUrl
    SpaceChildItem(
        child = child.copy(avatarUrl = resolvedAvatar),
        onClick = { viewModel.openChild(child) },
        onMoreClick = { viewModel.showMoveToSection(child.roomId) }
    )
}

@Composable
private fun SpaceHeaderCard(space: SpaceInfo, avatarPath: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = space.name,
                    avatarPath = avatarPath,
                    size = 56.dp
                )

                Spacer(Modifier.width(Spacing.lg))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            space.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (space.isPublic) {
                            Spacer(Modifier.width(Spacing.sm))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    stringResource(Res.string.public),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = Spacing.sm,
                                        vertical = 2.dp
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(Res.string.n_members, space.memberCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            space.topic?.let { topic ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    topic,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpaceChildItem(
    child: SpaceChildInfo,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                child.name ?: child.alias ?: child.roomId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = child.topic?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            val displayName = child.name ?: child.alias ?: child.roomId
            Avatar(
                name = displayName,
                avatarPath = child.avatarUrl,
                size = 40.dp,
                shape = MaterialTheme.shapes.small
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${child.memberCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onMoreClick) {
                    Icon(
                        Icons.Default.MoreVert,
                        stringResource(Res.string.move_to_section),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}