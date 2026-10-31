package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomHistoryVisibility
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.components.dialogs.InviteUserDialog
import org.mlm.mages.ui.components.sheets.GranularPermissionsSheet
import org.mlm.mages.ui.components.sheets.JoinRuleSpacePickerSheet
import org.mlm.mages.ui.components.sheets.KnockRequestsSheet
import org.mlm.mages.ui.components.sheets.MemberActionsSheet
import org.mlm.mages.ui.components.sheets.MemberListSheet
import org.mlm.mages.ui.components.sheets.PowerLevelsSheet
import org.mlm.mages.ui.components.sheets.ReportContentDialog
import org.mlm.mages.ui.components.sheets.RoomAliasesSheet
import org.mlm.mages.ui.components.sheets.RoomNotificationSheet
import org.mlm.mages.ui.components.settings.*
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.EditableAvatar
import org.mlm.mages.ui.components.core.TopicText
import org.mlm.mages.ui.theme.AppColors
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.koin.compose.koinInject
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.viewmodel.RoomInfoUiState
import org.mlm.mages.ui.viewmodel.RoomInfoViewModel
import org.mlm.mages.verification.VerificationCoordinator
import org.mlm.mages.matrix.displayName
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.launch
import org.mlm.mages.platform.toTransferItem
import org.mlm.mages.platform.RoomPlatformShortcuts
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.ui.util.dragToScrollIfEnabled
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import androidx.compose.runtime.Composable

@Composable
fun RoomInfoRoute(
    viewModel: RoomInfoViewModel,
    onBack: () -> Unit,
    onLeaveSuccess: () -> Unit,
    onOpenMediaGallery: () -> Unit,
    onOpenImagePackEditor: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onOpenSpace: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val verification: VerificationCoordinator = koinInject()
    val verState by verification.state.collectAsState()
    val postError = rememberErrorPoster(snackbarManager)
    val openExternal = rememberFileOpener()

    LaunchedEffect(verState.sasFlowId) {
        viewModel.refreshVerificationState()
    }

    val shortcutSupport = remember { RoomPlatformShortcuts.support() }


    val shortcutAddedText = stringResource(Res.string.shortcut_added)
    val shortcutFailedText = stringResource(Res.string.unable_to_add_shortcut)

    fun addHomeShortcut() {
        val roomId = state.profile?.roomId ?: return
        val roomName = state.profile?.name ?: state.editedName.takeIf { it.isNotBlank() }

        RoomPlatformShortcuts.addHomeScreenShortcut(roomId, roomName)
            .onSuccess {
                snackbarManager.show(shortcutAddedText)
            }
            .onFailure {
                postError(it.message ?: shortcutFailedText)
            }
    }

    RoomInfoScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onNameChange = viewModel::updateName,
        onTopicChange = viewModel::updateTopic,
        onSaveName = viewModel::saveName,
        onSaveTopic = viewModel::saveTopic,
        onSetAvatar = viewModel::setAvatar,
        onRemoveAvatar = viewModel::removeAvatar,
        onToggleFavourite = viewModel::toggleFavourite,
        onToggleLowPriority = viewModel::toggleLowPriority,
        onLeave = viewModel::leave,
        onSetVisibility = viewModel::setDirectoryVisibility,
        onEnableEncryption = viewModel::enableEncryption,
        onSetJoinRule = viewModel::requestJoinRule,
        onHideJoinRuleSpacePicker = viewModel::hideJoinRuleSpacePicker,
        onSaveJoinRuleSpaces = { rule, spaceIds -> viewModel.setJoinRule(rule, spaceIds) },
        onSetHistoryVisibility = viewModel::setHistoryVisibility,
        onUpdateAliases = viewModel::updateCanonicalAlias,
        onUpdatePowerLevel = viewModel::updatePowerLevel,
        onApplyPowerLevelChanges = viewModel::applyPowerLevelChanges,
        onReportRoom = viewModel::reportRoom,
        onOpenRoom = viewModel::openRoom,
        onOpenSpace = onOpenSpace,
        onOpenMediaGallery = onOpenMediaGallery,
        onOpenImagePackEditor = onOpenImagePackEditor,
        onOpenWallpaper = onOpenWallpaper,
        onShowNotificationSettings = viewModel::showNotificationSettings,
        onHideNotificationSettings = viewModel::hideNotificationSettings,
        onSetNotificationMode = viewModel::setNotificationMode,
        onShowMembers = viewModel::showMembers,
        onHideMembers = viewModel::hideMembers,
        onShowKnockRequests = viewModel::showKnockRequests,
        onHideKnockRequests = viewModel::hideKnockRequests,
        onSelectMember = viewModel::selectMemberForAction,
        onKickUser = viewModel::kickUser,
        onBanUser = viewModel::banUser,
        onUnbanUser = viewModel::unbanUser,
        onIgnoreUser = viewModel::ignoreUser,
        onStartDm = viewModel::startDmWith,
        onVerifyUser = { verification.startUserVerify(it) },
        onShowInviteDialog = viewModel::showInviteDialog,
        onHideInviteDialog = viewModel::hideInviteDialog,
        onInviteUser = viewModel::inviteUser,
        onAcceptKnockRequest = viewModel::acceptKnockRequest,
        onDeclineKnockRequest = viewModel::declineKnockRequest,
        onClearSelectedMember = viewModel::clearSelectedMember,
        onOpenAvatar = { viewModel.openAvatarExternally(it) { path, mime -> openExternal(path, mime) } },

        showHomeScreenShortcut = currentPlatform == SettingPlatform.ANDROID && shortcutSupport.homeScreenShortcut,
        onAddHomeScreenShortcut = ::addHomeShortcut,
    )
}

@Composable
fun RoomInfoScreen(
    state: RoomInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onSaveTopic: () -> Unit,
    onSetAvatar: (String, String) -> Unit,
    onRemoveAvatar: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit,
    onLeave: () -> Unit,
    onSetVisibility: (RoomDirectoryVisibility) -> Unit,
    onEnableEncryption: () -> Unit,
    onSetJoinRule: (RoomJoinRule) -> Unit,
    onHideJoinRuleSpacePicker: () -> Unit,
    onSaveJoinRuleSpaces: (RoomJoinRule, List<String>) -> Unit,
    onSetHistoryVisibility: (RoomHistoryVisibility) -> Unit,
    onUpdateAliases: (String?, List<String>) -> Unit,
    onUpdatePowerLevel: (String, Long) -> Unit,
    onApplyPowerLevelChanges: (RoomPowerLevelChanges) -> Unit,
    onReportRoom: (String?) -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenSpace: (String) -> Unit,
    onOpenMediaGallery: () -> Unit,
    onOpenImagePackEditor: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onShowNotificationSettings: () -> Unit,
    onHideNotificationSettings: () -> Unit,
    onSetNotificationMode: (RoomNotificationMode) -> Unit,
    onShowMembers: () -> Unit,
    onHideMembers: () -> Unit,
    onShowKnockRequests: () -> Unit,
    onHideKnockRequests: () -> Unit,
    onSelectMember: (MemberSummary) -> Unit,
    onKickUser: (String, String?) -> Unit,
    onBanUser: (String, String?) -> Unit,
    onUnbanUser: (String, String?) -> Unit,
    onIgnoreUser: (String) -> Unit,
    onStartDm: (String) -> Unit,
    onVerifyUser: (String) -> Unit,
    onShowInviteDialog: () -> Unit,
    onHideInviteDialog: () -> Unit,
    onInviteUser: (String) -> Unit,
    onAcceptKnockRequest: (String) -> Unit,
    onDeclineKnockRequest: (String, String?) -> Unit,
    onClearSelectedMember: () -> Unit,
    onOpenAvatar: (MemberSummary) -> Unit,

    showHomeScreenShortcut: Boolean,
    onAddHomeScreenShortcut: () -> Unit,
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showAliasesSheet by remember { mutableStateOf(false) }
    var showPowerLevelsSheet by remember { mutableStateOf(false) }
    var showGranularPermissionsSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val clipboard = LocalClipboardManager.current
    val dmPartner = state.dmPartner

    val pickScope = rememberCoroutineScope()
    var pendingAvatarPick by remember { mutableStateOf<((String, String) -> Unit)?>(null) }
    val avatarPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        val onPicked = pendingAvatarPick ?: return@rememberFilePickerLauncher
        pendingAvatarPick = null
        val picked = file ?: return@rememberFilePickerLauncher
        pickScope.launch {
            val path = picked.toTransferItem().path ?: return@launch
            onPicked(path, picked.mimeType()?.toString() ?: "image/png")
        }
    }
    val pickRoomAvatar: (() -> Unit)? = if (state.canEditAvatar) {
        {
            pendingAvatarPick = { path, mime -> onSetAvatar(path, mime) }
            avatarPicker.launch()
        }
    } else {
        null
    }

    LaunchedEffect(state.error) { state.error?.let { postError(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.room_info)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.refresh))
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading && state.profile == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).dragToScrollIfEnabled(listState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    ProfileHeader(
                        state = state,
                        onPickAvatar = pickRoomAvatar,
                    )
                }

                state.successor?.let { successor ->
                    item {
                        UpgradeBanner(
                            title = stringResource(Res.string.this_room_has_been_upgraded),
                            reason = successor.reason,
                            buttonText = stringResource(Res.string.go_to_new_room),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            onAction = { onOpenRoom(successor.roomId) }
                        )
                    }
                }
                state.predecessor?.let { predecessor ->
                    item {
                        UpgradeBanner(
                            title = stringResource(Res.string.upgraded_from_another_room),
                            buttonText = stringResource(Res.string.open_previous_room),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            onAction = { onOpenRoom(predecessor.roomId) }
                        )
                    }
                }

                item {
                    QuickActions(
                        isFavourite = state.isFavourite,
                        isLowPriority = state.isLowPriority,
                        isSaving = state.isSaving,
                        onToggleFavourite = onToggleFavourite,
                        onToggleLowPriority = onToggleLowPriority
                    )
                }

                if (dmPartner != null && state.profile?.isEncrypted == true) {
                    item {
                        SettingsGroup {
                            if (state.dmPartnerVerified) {
                                SettingsActionRow(
                                    icon = Icons.Default.VerifiedUser,
                                    title = stringResource(Res.string.verified),
                                    iconTint = AppColors.Verified,
                                    enabled = false,
                                    onClick = {}
                                )
                            } else {
                                SettingsActionRow(
                                    icon = Icons.Default.VerifiedUser,
                                    title = stringResource(Res.string.verify_user),
                                    subtitle = dmPartner.displayName ?: dmPartner.userId,
                                    actionText = stringResource(Res.string.start),
                                    onClick = { onVerifyUser(dmPartner.userId) }
                                )
                            }
                        }
                    }
                }

                if (state.canEditName || state.canEditTopic) {
                    item {
                        SettingsGroup {
                            if (state.canEditName) {
                                EditableSettingField(
                                    label = stringResource(Res.string.room_name),
                                    value = state.editedName,
                                    onValueChange = onNameChange,
                                    onSave = onSaveName,
                                    enabled = true,
                                    isSaving = state.isSaving
                                )
                                if (state.canEditTopic) {
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                }
                            }
                            if (state.canEditTopic) {
                                EditableSettingField(
                                    label = stringResource(Res.string.topic),
                                    value = state.editedTopic,
                                    onValueChange = onTopicChange,
                                    onSave = onSaveTopic,
                                    enabled = true,
                                    isSaving = state.isSaving,
                                    singleLine = false
                                )
                            }
                        }
                    }
                }

                if (state.canEditAvatar && state.profile?.avatarUrl != null) {
                    item {
                        SettingsGroup {
                            SettingsActionRow(
                                icon = Icons.Default.DeleteOutline,
                                title = stringResource(Res.string.profile_remove_photo),
                                enabled = !state.isSavingAvatar,
                                onClick = onRemoveAvatar
                            )
                        }
                    }
                }

                if (state.parentSpaces.isNotEmpty()) {
                    item {
                        SettingsGroup {
                            state.parentSpaces.forEachIndexed { index, space ->
                                if (index > 0) {
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                }
                                SettingsAvatarNavRow(
                                    avatarPath = space.avatarUrl,
                                    avatarName = space.name ?: space.spaceId,
                                    title = space.name ?: space.spaceId,
                                    onClick = { onOpenSpace(space.spaceId) }
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsGroup {
                        SettingsNavRow(
                            icon = Icons.Default.People,
                            title = stringResource(Res.string.members_room),
                            subtitle = stringResource(Res.string.n_members, state.members.size),
                            onClick = onShowMembers
                        )
                        if (state.canInvite) {
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.HowToReg,
                                title = stringResource(Res.string.knock_requests),
                                subtitle = if (state.knockRequests.isEmpty()) stringResource(Res.string.no_pending_requests) else "${state.knockRequests.size} pending",
                                onClick = onShowKnockRequests
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsNavRow(
                            icon = Icons.Default.Notifications,
                            title = stringResource(Res.string.notifications),
                            subtitle = state.notificationMode?.displayName() ?: stringResource(Res.string.default),
                            onClick = onShowNotificationSettings
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsNavRow(
                            icon = Icons.Default.Wallpaper,
                            title = stringResource(Res.string.setting_chat_wallpaper),
                            onClick = onOpenWallpaper
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsNavRow(
                            icon = Icons.Default.PhotoLibrary,
                            title = stringResource(Res.string.media_files),
                            onClick = onOpenMediaGallery
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsNavRow(
                            icon = Icons.Default.CollectionsBookmark,
                            title = stringResource(Res.string.image_packs),
                            onClick = onOpenImagePackEditor
                        )
                    }
                }

                val showSecuritySection = state.profile?.let { it.isEncrypted || state.canManageSettings } == true
                if (showSecuritySection) {
                    item {
                        SettingsGroupHeader(stringResource(Res.string.security_and_access))
                        SettingsGroup {
                            state.profile.let { profile ->
                                if (profile.isEncrypted) {
                                    SettingsInfoRow(
                                        icon = Icons.Default.Lock,
                                        title = stringResource(Res.string.encryption),
                                        value = stringResource(Res.string.enabled)
                                    )
                                    if (state.canManageSettings) {
                                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    }
                                } else if (state.canManageSettings) {
                                    SettingsActionRow(
                                        icon = Icons.Default.LockOpen,
                                        title = stringResource(Res.string.encryption),
                                        subtitle = stringResource(Res.string.not_enabled),
                                        actionText = stringResource(Res.string.enable),
                                        enabled = !state.isAdminBusy,
                                        onClick = onEnableEncryption
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                }

                                if (state.canManageSettings) {
                                    val joinRule = state.joinRule
                                    val joinRuleSubtitle = when (joinRule) {
                                        RoomJoinRule.Restricted,
                                        RoomJoinRule.KnockRestricted -> {
                                            val count = state.joinRuleAllowedSpaceIds.size
                                            val spaceWord = if (count == 1) "space" else "spaces"
                                            stringResource(Res.string.join_rule_n_spaces_allowed, joinRule.displayName(), count, spaceWord)
                                        }
                                        else -> null
                                    }
                                    SettingsDropdownRow(
                                        icon = Icons.Default.MeetingRoom,
                                        label = stringResource(Res.string.who_can_join),
                                        currentValue = state.joinRule,
                                        displayName = { it.displayName() },
                                        subtitle = joinRuleSubtitle,
                                        options = listOf(
                                            RoomJoinRule.Public to stringResource(Res.string.public_anyone_can_join),
                                            RoomJoinRule.Invite to stringResource(Res.string.invite_only),
                                            RoomJoinRule.Knock to stringResource(Res.string.knock_ask_to_join),
                                            RoomJoinRule.Restricted to stringResource(Res.string.space_members_can_join),
                                            RoomJoinRule.KnockRestricted to stringResource(Res.string.ask_to_join_with_space_members),
                                        ),
                                        enabled = !state.isAdminBusy,
                                        canChange = true,
                                        onSelect = onSetJoinRule
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    SettingsDropdownRow(
                                        icon = Icons.Default.History,
                                        label = stringResource(Res.string.message_history),
                                        currentValue = state.historyVisibility,
                                        displayName = { it.displayName() },
                                        options = listOf(
                                            RoomHistoryVisibility.WorldReadable to stringResource(Res.string.anyone),
                                            RoomHistoryVisibility.Shared to stringResource(Res.string.all_members),
                                            RoomHistoryVisibility.Joined to stringResource(Res.string.since_joined),
                                            RoomHistoryVisibility.Invited to stringResource(Res.string.since_invited)
                                        ),
                                        enabled = !state.isAdminBusy,
                                        canChange = true,
                                        onSelect = onSetHistoryVisibility
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    SettingSwitchRow(
                                        icon = Icons.Default.Public,
                                        title = stringResource(Res.string.listed_in_room_directory),
                                        checked = state.directoryVisibility == RoomDirectoryVisibility.Public,
                                        enabled = !state.isAdminBusy,
                                        onCheckedChange = { checked ->
                                            onSetVisibility(if (checked) RoomDirectoryVisibility.Public else RoomDirectoryVisibility.Private)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.canManageSettings) {
                    item {
                        SettingsGroupHeader(stringResource(Res.string.permissions))
                        SettingsGroup {
                            SettingsInfoRow(
                                icon = Icons.Default.Badge,
                                title = stringResource(Res.string.your_role),
                                value = getRoleName(state.myPowerLevel)
                            )
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.AdminPanelSettings,
                                title = stringResource(Res.string.room_permissions_section),
                                subtitle = stringResource(Res.string.configure_what_each_role_can_do),
                                onClick = { showGranularPermissionsSheet = true }
                            )
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.Shield,
                                title = stringResource(Res.string.manage_roles),
                                subtitle = stringResource(Res.string.change_member_power_levels),
                                onClick = { showPowerLevelsSheet = true }
                            )
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.Edit,
                                title = stringResource(Res.string.room_addresses),
                                subtitle = state.profile?.canonicalAlias ?: stringResource(Res.string.no_primary_address),
                                onClick = { showAliasesSheet = true }
                            )
                        }
                    }
                }

                if (showHomeScreenShortcut) {
                    item {
                        SettingsGroupHeader(stringResource(Res.string.shortcuts))
                        SettingsGroup {
                            SettingsActionRow(
                                icon = Icons.Default.Home,
                                title = stringResource(Res.string.add_to_home_screen),
                                subtitle = stringResource(Res.string.create_a_launcher_shortcut_for_this_room),
                                onClick = onAddHomeScreenShortcut
                            )
                        }
                    }
                }

                item {
                    SettingsGroupHeader(stringResource(Res.string.advanced))
                    SettingsGroup {
                        SettingsNavRow(
                            icon = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            title = if (showAdvanced) stringResource(Res.string.hide_advanced) else stringResource(Res.string.show_advanced),
                            onClick = { showAdvanced = !showAdvanced }
                        )
                        if (showAdvanced) {
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsInfoRow(
                                icon = Icons.Default.Info,
                                title = stringResource(Res.string.room_version),
                                value = state.profile?.roomVersion ?: stringResource(Res.string.unknown)
                            )
                            state.profile?.roomId?.let { roomId ->
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsCopyRow(
                                    icon = Icons.Default.Tag,
                                    title = stringResource(Res.string.room_id),
                                    value = roomId,
                                    onCopy = { clipboard.setText(AnnotatedString(roomId)) }
                                )
                            }
                            state.profile?.canonicalAlias?.let { alias ->
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsCopyRow(
                                    icon = Icons.Default.AlternateEmail,
                                    title = stringResource(Res.string.primary_address),
                                    value = alias,
                                    onCopy = { clipboard.setText(AnnotatedString(alias)) }
                                )
                            }
                            val altCount = state.profile?.altAliases?.size ?: 0
                            if (altCount > 0) {
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsInfoRow(
                                    icon = Icons.Default.Link,
                                    title = stringResource(Res.string.alternative_addresses),
                                    value = "$altCount"
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.lg))
                    SettingsGroup {
                        SettingsDangerRow(
                            icon = Icons.Default.Report,
                            title = stringResource(Res.string.report_this_room),
                            onClick = { showReportDialog = true }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsDangerRow(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            title = if (state.profile?.isDm == true) stringResource(Res.string.end_conversation) else stringResource(Res.string.leave_room_action),
                            onClick = { showLeaveDialog = true },
                            enabled = !state.isSaving
                        )
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }

        if (showLeaveDialog) {
            ConfirmationDialog(
                title = stringResource(Res.string.leave_room),
                message = stringResource(Res.string.you_will_no_longer_receive_messages_from_this_room_you_can_rejoin_if_invited_again),
                confirmText = stringResource(Res.string.leave),
                icon = Icons.Default.Warning,
                isDestructive = true,
                isLoading = state.isSaving,
                onConfirm = { showLeaveDialog = false; onLeave() },
                onDismiss = { showLeaveDialog = false }
            )
        }

        if (state.showNotificationSettings) {
            RoomNotificationSheet(
                currentMode = state.notificationMode,
                isLoading = state.isLoadingNotificationMode,
                onModeChange = onSetNotificationMode,
                onDismiss = onHideNotificationSettings
            )
        }
        if (showAliasesSheet) {
            RoomAliasesSheet(
                canonicalAlias = state.profile?.canonicalAlias,
                altAliases = state.profile?.altAliases ?: emptyList(),
                onUpdate = { canonical, alts -> onUpdateAliases(canonical, alts); showAliasesSheet = false },
                onDismiss = { showAliasesSheet = false }
            )
        }
        if (showPowerLevelsSheet) {
            PowerLevelsSheet(
                members = state.members,
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevel = onUpdatePowerLevel,
                onDismiss = { showPowerLevelsSheet = false }
            )
        }
        if (state.showJoinRuleSpacePicker && state.pendingJoinRule != null) {
            JoinRuleSpacePickerSheet(
                rule = state.pendingJoinRule,
                spaces = state.selectableSpaces,
                initiallyAllowedSpaceIds = state.joinRuleAllowedSpaceIds,
                onSave = onSaveJoinRuleSpaces,
                onDismiss = onHideJoinRuleSpacePicker,
            )
        }
        if (showGranularPermissionsSheet) {
            GranularPermissionsSheet(
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevels = onApplyPowerLevelChanges,
                onDismiss = { showGranularPermissionsSheet = false }
            )
        }
        if (showReportDialog) {
            ReportContentDialog(
                onReport = { reason -> onReportRoom(reason); showReportDialog = false },
                onDismiss = { showReportDialog = false }
            )
        }

        if (state.showMembers) {
            MemberListSheet(
                members = state.members,
                bannedMembers = state.bannedMembers,
                isLoading = false,
                myUserId = state.myUserId,
                activeCallParticipants = state.activeCallParticipants,
                onDismiss = onHideMembers,
                onMemberClick = onSelectMember,
                onInvite = onShowInviteDialog
            )
        }

        if (state.showKnockRequests) {
            KnockRequestsSheet(
                requests = state.knockRequests,
                onDismiss = onHideKnockRequests,
                onAccept = onAcceptKnockRequest,
                onDecline = onDeclineKnockRequest,
            )
        }

        state.selectedMemberForAction?.let { member ->
            MemberActionsSheet(
                member = member,
                onDismiss = onClearSelectedMember,
                onStartDm = { onStartDm(member.userId) },
                onKick = { reason -> onKickUser(member.userId, reason) },
                onBan = { reason -> onBanUser(member.userId, reason) },
                onUnban = { reason -> onUnbanUser(member.userId, reason) },
                onIgnore = { onIgnoreUser(member.userId) },
                onAvatarClick = { onOpenAvatar(member) },
                onVerify = if (!state.dmPartnerVerified && dmPartner?.userId == member.userId && state.profile?.isEncrypted == true) {
                    { onVerifyUser(member.userId) }
                } else {
                    null
                },
                verified = state.dmPartnerVerified && dmPartner?.userId == member.userId,
                dmAction = state.selectedMemberDmAction,
                kickAction = state.selectedMemberKickAction,
                banAction = state.selectedMemberBanAction,
                unbanAction = state.selectedMemberUnbanAction,
                isBanned = member.membership == "ban"
            )
        }

        if (state.showInviteDialog) {
            InviteUserDialog(
                onInvite = onInviteUser,
                onDismiss = onHideInviteDialog
            )
        }
    }
}


@Composable
private fun ProfileHeader(
    state: RoomInfoUiState,
    onPickAvatar: (() -> Unit)? = null,
) {
    val profile = state.profile ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EditableAvatar(
            name = profile.name,
            avatarPath = profile.avatarUrl,
            size = 80.dp,
            shape = MaterialTheme.shapes.large,
            isSaving = state.isSavingAvatar,
            onClick = onPickAvatar,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (profile.isEncrypted) {
                Icon(Icons.Default.Lock, stringResource(Res.string.encrypted), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        profile.canonicalAlias?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (profile.topic?.isNotBlank() == true || profile.topicHtml?.isNotBlank() == true) {
            Spacer(Modifier.height(Spacing.sm))
            TopicText(
                topic = profile.topic,
                topicHtml = profile.topicHtml,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun QuickActions(
    isFavourite: Boolean,
    isLowPriority: Boolean,
    isSaving: Boolean,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        FilterChip(
            selected = isFavourite,
            onClick = onToggleFavourite,
            label = { Text(stringResource(Res.string.favourite_room)) },
            leadingIcon = {
                Icon(
                    if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    null, Modifier.size(18.dp)
                )
            },
            enabled = !isSaving
        )
        FilterChip(
            selected = isLowPriority,
            onClick = onToggleLowPriority,
            label = { Text(stringResource(Res.string.low_priority)) },
            leadingIcon = {
                Icon(
                    if (isLowPriority) Icons.Default.ArrowDownward else Icons.Default.Remove,
                    null, Modifier.size(18.dp)
                )
            },
            enabled = !isSaving
        )
    }
}

@Composable
private fun UpgradeBanner(
    title: String,
    reason: String? = null,
    buttonText: String,
    containerColor: Color,
    onAction: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            reason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(buttonText) }
        }
    }
}

/** Groups settings rows inside a card with consistent padding. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
    )
}

/** A row that shows icon + title + subtitle and navigates on click. */
@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row that leads with an avatar instead of an icon and navigates on click. */
@Composable
private fun SettingsAvatarNavRow(
    avatarPath: String?,
    avatarName: String,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                name = avatarName,
                avatarPath = avatarPath,
                size = Sizes.avatarSmall
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row showing a label and a static value. */
@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row with a value that can be copied (tap anywhere to copy). */
@Composable
private fun SettingsCopyRow(
    icon: ImageVector,
    title: String,
    value: String,
    onCopy: () -> Unit
) {
    Surface(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ContentCopy, stringResource(Res.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// Duplicated composables (kmp-settings - likes)

/** A row with icon + title + optional subtitle, with an action button on the right. */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actionText?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A destructive action row (red tinted). */
@Composable
private fun SettingsDangerRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
            )
        }
    }
}

/** Dropdown setting row with icon, matching the grouped list style. */
@Composable
private fun <T> SettingsDropdownRow(
    icon: ImageVector,
    label: String,
    currentValue: T?,
    displayName: @Composable (T) -> String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    canChange: Boolean,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (canChange) expanded = it }
    ) {
        Surface(
            onClick = { if (canChange && enabled) expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        subtitle ?: (currentValue?.let { displayName(it) } ?: stringResource(Res.string.unknown)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canChange) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (canChange) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(value); expanded = false },
                        leadingIcon = if (currentValue == value) {
                            { Icon(Icons.Default.Check, null) }
                        } else null
                    )
                }
            }
        }
    }
}


@Composable
private fun getRoleName(powerLevel: Long): String = when {
    powerLevel >= 100 -> stringResource(Res.string.admin)
    powerLevel >= 50 -> stringResource(Res.string.moderator)
    powerLevel > 0 -> stringResource(Res.string.role_custom_level, powerLevel)
    else -> stringResource(Res.string.user)
}

@Composable
private fun RoomJoinRule.displayName(): String = when (this) {
        RoomJoinRule.Public -> stringResource(Res.string.public)
        RoomJoinRule.Invite -> stringResource(Res.string.invite_only)
        RoomJoinRule.Knock -> stringResource(Res.string.knock)
        RoomJoinRule.Restricted -> stringResource(Res.string.restricted)
        RoomJoinRule.KnockRestricted -> stringResource(Res.string.knock_restricted)
    }

@Composable
private fun RoomHistoryVisibility.displayName(): String = when (this) {
        RoomHistoryVisibility.WorldReadable -> stringResource(Res.string.visible_to_anyone)
        RoomHistoryVisibility.Shared -> stringResource(Res.string.visible_to_all_members)
        RoomHistoryVisibility.Joined -> stringResource(Res.string.since_joined)
        RoomHistoryVisibility.Invited -> stringResource(Res.string.since_invited)
    }
