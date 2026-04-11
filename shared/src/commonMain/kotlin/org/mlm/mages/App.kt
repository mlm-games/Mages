package org.mlm.mages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.calls.CallManager
import org.mlm.mages.matrix.Presence
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.nav.*
import org.mlm.mages.platform.BindLifecycle
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.LocalAppLocale
import org.mlm.mages.platform.ProvideAppLocale
import org.mlm.mages.platform.platformEmbeddedElementCallParentUrlOrNull
import org.mlm.mages.platform.platformEmbeddedElementCallUrlOrNull
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.settings.appLanguageTagOrNull
import org.mlm.mages.ui.GlobalCallOverlay
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
import org.mlm.mages.ui.components.dialogs.SasDialog
import org.mlm.mages.ui.components.sheets.AccountSwitcherSheet
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.components.snackbar.LauncherSnackbarHost
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.screens.*
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.*
import org.mlm.mages.verification.VerificationCoordinator
import org.mlm.mages.matrix.CallIntent

val LocalMessageFontSize = staticCompositionLocalOf { 16f }

@Composable
fun App(
    settingsRepository: SettingsRepository<AppSettings>,
    deepLinks: Flow<DeepLinkAction>? = null,
    onRequestLocationPermissions: ((() -> Unit) -> Unit)? = null,
    onRequestVideoCallPermissions: ((() -> Unit) -> Unit)? = null,
    onRequestVoiceCallPermissions: ((() -> Unit) -> Unit)? = null
) {
    val settings by settingsRepository.flow.collectAsState(AppSettings())

    CompositionLocalProvider(LocalMessageFontSize provides settings.fontSize) {
        AppContent(
            deepLinks = deepLinks,
            onRequestLocationPermissions = onRequestLocationPermissions,
            onRequestVideoCallPermissions = onRequestVideoCallPermissions,
            onRequestVoiceCallPermissions = onRequestVoiceCallPermissions,
        )
    }
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun AppContent(
    deepLinks: Flow<DeepLinkAction>?,
    onRequestLocationPermissions: ((() -> Unit) -> Unit)? = null,
    onRequestVideoCallPermissions: ((() -> Unit) -> Unit)? = null,
    onRequestVoiceCallPermissions: ((() -> Unit) -> Unit)? = null
) {
    val service: MatrixService = koinInject()
    val accountStore: AccountStore = koinInject()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val snackbarManager: SnackbarManager = koinInject()
    val snackbarHostState: SnackbarHostState = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val callManager: CallManager = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())

    if (currentPlatform == SettingPlatform.WEB) {
        BindNotifications(service = service, settingsRepository = settingsRepository)
    } //NOTE: web notifs , causes duplicate notifs on desktop if used there too (due to it being called on DesktopAppContent already).

    var initDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { service.initFromDisk() }
        val oauthCompleted = runCatching { service.port.maybeFinishOauthRedirect() }.getOrDefault(false)
        if (oauthCompleted) {
            runCatching { service.initFromDisk() }
        }
        initDone = true
    }

    val activeAccount by service.activeAccount.collectAsState()
    val activeId = activeAccount?.id

    val verification: VerificationCoordinator = koinInject()
    val verState by verification.state.collectAsState()

    if (!initDone) {
        Surface(color = darkColorScheme().background) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularWavyProgressIndicator() }
        }
        return
    }

    val initialRoute = remember(activeId) {
        if (activeId != null && service.isLoggedIn()) Route.Rooms else Route.Login
    }

    // isLoggedIn() calls in remember{} are non-suspend and safe for initialization

    val isDark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val widgetTheme = if (isDark) "dark" else "light"
    val elementCallUrl =
        settings.elementCallUrl.trim().ifBlank { platformEmbeddedElementCallUrlOrNull() }
    val parentCallUrl = platformEmbeddedElementCallParentUrlOrNull()

    ProvideAppLocale(settings.appLanguageTagOrNull()) {
        MainTheme(
            darkTheme = isDark,
            dynamicColors = settings.dynamicColors
        ) {
            val languageTag = LocalAppLocale.current
            val scope = rememberCoroutineScope()
            var sessionEpoch by remember { mutableIntStateOf(0) }
            var showCreateRoom by remember { mutableStateOf(false) }
            var showAccountSwitcher by remember { mutableStateOf(false) }
            val accounts by accountStore.accounts.collectAsState()
            val activeAccountId by accountStore.activeAccountId.collectAsState()

            val backStack: NavBackStack<NavKey> =
                rememberNavBackStack(navSavedStateConfiguration, initialRoute)

            BindDeepLinks(
                backStack,
                deepLinks,
                callManager,
                widgetTheme,
                languageTag,
                elementCallUrl,
                parentCallUrl,
                onRequestVideoCallPermissions
            )

            BindLifecycle(service, resetSyncState = true)

            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) {
                    backStack.clear()
                    backStack.add(Route.Login)
                    return@LaunchedEffect
                } else {
                    if (backStack.lastOrNull() == Route.Login) {
                        backStack.replaceTop(Route.Rooms)
                    }
                }
            }

            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                settingsRepository.flow.collect { s ->
                    val presence = when (s.presence) {
                        org.mlm.mages.settings.PresenceMode.Online -> Presence.Online
                        org.mlm.mages.settings.PresenceMode.Offline -> Presence.Offline
                        org.mlm.mages.settings.PresenceMode.Unavailable -> Presence.Unavailable
                    }
                    runCatching { service.port.setPresence(presence, null) }
                }
            }

            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                service.resetSyncState()
                service.startSupervisedSync()
            }

            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                service.port.observeSends().collect { update ->
                    if (update.txnId.isBlank() && update.error?.contains("send queue disabled") == true) {
                        snackbarManager.show(
                            message = "Sending paused",
                            actionLabel = "Resume",
                            duration = SnackbarDuration.Indefinite,
                            onAction = { runCatching { service.port.sendQueueSetEnabled(true) } }
                        )
                    }
                }
            }

            val uriHandler = LocalUriHandler.current
            val openUrl: (String) -> Boolean = remember(uriHandler) {
                { url -> runCatching { uriHandler.openUri(url); true }.getOrDefault(false) }
            }
            val callState by callManager.call.collectAsState()
            val callOverlayActive = callState != null
            Scaffold(
                snackbarHost = {
                    LauncherSnackbarHost(hostState = snackbarHostState, manager = snackbarManager)
                }
            ) { _ ->
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator()
                    ),
                    transitionSpec = forwardTransition,
                    popTransitionSpec = popTransition,
                    predictivePopTransitionSpec = { _ -> popTransition.invoke(this) },
                    onBack = {
                        val top = backStack.lastOrNull()
                        val isInitialLogin = top == Route.Login && backStack.size == 1
                        val isRoomsRoot = top == Route.Rooms

                        when {
                            callOverlayActive -> {
                                callManager.setMinimized(true)
                                // Handle via or put it in here to not go back
                            }

                            isInitialLogin || isRoomsRoot -> {
                                // block back
                            }

                            backStack.size > 1 -> {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        }
                    },
                    entryProvider = entryProvider {

                        entry<Route.Login>(metadata = loginEntryFadeMetadata()) {
                            val viewModel: LoginViewModel = koinViewModel()
                            val isAddingAccount = backStack.size > 1

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        LoginViewModel.Event.LoginSuccess -> {
                                            // activeId effect above will move auto. to Rooms
                                        }
                                    }
                                }
                            }

                            LoginScreen(
                                viewModel = viewModel,
                                onSso = { viewModel.startSso(openUrl) },
                                onOauth = { viewModel.startOauth(openUrl) },
                                isAddingAccount = isAddingAccount
                            )
                        }

                        entry<Route.Rooms> {
                            val viewModel: RoomsViewModel = koinViewModel()

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is RoomsViewModel.Event.OpenRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.name))
                                        }

                                        is RoomsViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }
                                    }
                                }
                            }

                            RoomsScreen(
                                viewModel = viewModel,
                                onOpenSecurity = { backStack.add(Route.Security) },
                                onOpenDiscover = { backStack.add(Route.Discover) },
                                onOpenCreateRoom = { showCreateRoom = true },
                                onOpenSpaces = { backStack.add(Route.Spaces) },
                                onOpenSearch = { backStack.add(Route.Search) },
                            )

                            if (showCreateRoom) {
                                CreateRoomSheet(
                                    matrixPort = service.port,
                                    onCreate = { name, topic, invitees, isPublic, roomAlias ->
                                        scope.launch {
                                            val roomId = service.port.createRoom(
                                                name,
                                                topic,
                                                invitees,
                                                isPublic,
                                                roomAlias
                                            )
                                            if (roomId != null) {
                                                showCreateRoom = false
                                                backStack.add(Route.Room(roomId, name ?: roomId))
                                            } else {
                                                throw IllegalStateException("Failed to create room")
                                            }
                                        }
                                    },
                                    onDismiss = { showCreateRoom = false }
                                )
                            }
                        }

                        entry<Route.Room> { key ->
                            val viewModel: RoomViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId, key.name) }
                            )

                            RoomScreen(
                                viewModel = viewModel,
                                initialScrollToEventId = key.eventId,
                                onBack = backStack::popBack,
                                onOpenInfo = { backStack.add(Route.RoomInfo(key.roomId)) },
                                onNavigateToRoom = { roomId, name ->
                                    backStack.add(
                                        Route.Room(
                                            roomId,
                                            name
                                        )
                                    )
                                },
                                onNavigateToThread = { roomId, eventId, roomName ->
                                    backStack.add(Route.Thread(roomId, eventId, roomName))
                                },
                                onRequestLocationPermissions = onRequestLocationPermissions,
                                onStartCall = {
                                    onRequestVideoCallPermissions?.invoke {
                                        viewModel.startCall(
                                            intent = CallIntent.StartCall,
                                            theme = widgetTheme,
                                            languageTag = languageTag,
                                        )
                                    } ?: viewModel.startCall(
                                        intent = CallIntent.StartCall,
                                        theme = widgetTheme,
                                        languageTag = languageTag,
                                    )
                                },
                                onStartVoiceCall = {
                                    onRequestVoiceCallPermissions?.invoke {
                                        viewModel.startVoiceCall(
                                            languageTag = languageTag,
                                            theme = widgetTheme
                                        )
                                    } ?: viewModel.startVoiceCall(
                                        languageTag = languageTag,
                                        theme = widgetTheme
                                    )
                                },
                                onOpenForwardPicker = { roomId, eventIds ->
                                    backStack.add(Route.ForwardPicker(roomId, eventIds))
                                }
                            )
                        }

                        entry<Route.Security> {
                            val quitApp = rememberQuitApp()
                            val viewModel: SecurityViewModel = koinViewModel()

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is SecurityViewModel.Event.LogoutSuccess -> {
                                            sessionEpoch++
                                            backStack.replaceTop(Route.Login)
                                            quitApp()
                                        }

                                        is SecurityViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }

                                        is SecurityViewModel.Event.ShowSuccess -> {
                                            snackbarManager.show(event.message)
                                        }

                                        SecurityViewModel.Event.NavigateToNotificationRules -> {
                                            backStack.add(Route.NotificationRules)
                                        }

                                        SecurityViewModel.Event.NavigateToMediaCache -> {
                                            backStack.add(Route.MediaCache)
                                        }
                                    }
                                }
                            }

                            SecurityScreen(
                                viewModel = viewModel,
                                backStack = backStack,
                                onOpenAccountSwitcher = { showAccountSwitcher = true }
                            )

                            if (showAccountSwitcher) {
                                AccountSwitcherSheet(
                                    accounts = accounts,
                                    activeAccountId = activeAccountId,
                                    onSelectAccount = { account ->
                                        scope.launch {
                                            val success = service.switchAccount(account)
                                            if (success) {
                                                sessionEpoch++
                                                snackbarManager.show("Switched to ${account.userId}")
                                            } else {
                                                snackbarManager.showError("Failed to switch account")
                                            }
                                        }
                                    },
                                    onAddAccount = {
                                        showAccountSwitcher = false
                                        backStack.add(Route.Login)
                                    },
                                    onRemoveAccount = { account ->
                                        scope.launch {
                                            service.removeAccount(account.id)
                                            if (!service.isLoggedInSuspend()) {
                                                backStack.replaceTop(Route.Login)
                                            } else {
                                                sessionEpoch++
                                            }
                                        }
                                    },
                                    onDismiss = { showAccountSwitcher = false }
                                )
                            }
                        }

                        entry<Route.Discover> {
                            val viewModel: DiscoverViewModel = koinViewModel()

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is DiscoverViewModel.Event.OpenRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.name))
                                        }

                                        is DiscoverViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }
                                    }
                                }
                            }

                            DiscoverRoute(
                                viewModel = viewModel,
                                onClose = backStack::popBack
                            )
                        }

                        entry<Route.RoomInfo> { key ->
                            val viewModel: RoomInfoViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId) }
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is RoomInfoViewModel.Event.LeaveSuccess -> {
                                            backStack.popUntil { it is Route.Rooms }
                                        }

                                        is RoomInfoViewModel.Event.OpenRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.name))
                                        }

                                        is RoomInfoViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }

                                        is RoomInfoViewModel.Event.ShowSuccess -> {
                                            snackbarManager.show(event.message)
                                        }

                                        RoomInfoViewModel.Event.LeaveSuccess -> {
                                            backStack.popUntil { it is Route.Rooms }
                                        }
                                    }
                                }
                            }

                            RoomInfoRoute(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onLeaveSuccess = { backStack.popUntil { it is Route.Rooms } },
                                onOpenMediaGallery = { backStack.add(Route.MediaGallery(key.roomId)) }
                            )
                        }

                        entry<Route.Thread> { key ->
                            val viewModel: ThreadViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId, key.rootEventId) }
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is ThreadViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }

                                        is ThreadViewModel.Event.ShowSuccess -> {
                                            snackbarManager.show(event.message)
                                        }
                                    }
                                }
                            }

                            ThreadRoute(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                            )
                        }

                        entry<Route.Spaces> {
                            val viewModel: SpacesViewModel = koinViewModel()

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is SpacesViewModel.Event.OpenSpace -> {
                                            backStack.add(
                                                Route.SpaceDetail(
                                                    event.spaceId,
                                                    event.name
                                                )
                                            )
                                        }

                                        is SpacesViewModel.Event.OpenRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.name))
                                        }

                                        is SpacesViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }

                                        else -> {}
                                    }
                                }
                            }

                            SpacesScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack
                            )
                        }

                        entry<Route.SpaceDetail> { key ->
                            val viewModel: SpaceDetailViewModel = koinViewModel(
                                parameters = { parametersOf(key.spaceId, key.spaceName) }
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is SpaceDetailViewModel.Event.OpenSpace -> {
                                            backStack.add(
                                                Route.SpaceDetail(
                                                    event.spaceId,
                                                    event.name
                                                )
                                            )
                                        }

                                        is SpaceDetailViewModel.Event.OpenRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.name))
                                        }

                                        is SpaceDetailViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }
                                    }
                                }
                            }

                            SpaceDetailScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onOpenSettings = { backStack.add(Route.SpaceSettings(key.spaceId)) }
                            )
                        }

                        entry<Route.SpaceSettings> { key ->
                            val viewModel: SpaceSettingsViewModel = koinViewModel(
                                parameters = { parametersOf(key.spaceId) }
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is SpaceSettingsViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }

                                        is SpaceSettingsViewModel.Event.ShowSuccess -> {
                                            snackbarManager.show(event.message)
                                        }

                                        SpaceSettingsViewModel.Event.LeaveSuccess -> {
                                            backStack.popBack()
                                            backStack.popBack()
                                        }
                                    }
                                }
                            }

                            SpaceSettingsScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onLeaveSuccess = {
                                    // Pop noth SpaceSettings and SpaceDetail to return to room list
                                    backStack.popBack()
                                    backStack.popBack()
                                }
                            )
                        }

                        entry<Route.Search> {
                            val viewModel: SearchViewModel = koinViewModel(
                                parameters = { parametersOf(null, null) } // Global search
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is SearchViewModel.Event.OpenResult -> {
                                            backStack.add(
                                                Route.Room(
                                                    event.roomId,
                                                    event.roomName,
                                                    event.eventId
                                                )
                                            )
                                        }

                                        is SearchViewModel.Event.ShowError -> {
                                            postError(event.message)
                                        }
                                    }
                                }
                            }

                            SearchScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onOpenResult = { roomId, eventId, roomName ->
                                    backStack.add(Route.Room(roomId, roomName, eventId))
                                }
                            )
                        }

                        entry<Route.MediaGallery> { key ->
                            val viewModel: MediaGalleryViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId) }
                            )
                            val openExternal = rememberFileOpener()

                            MediaGalleryScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onOpenAttachment = { event ->
                                    event.attachment?.let { att ->
                                        scope.launch {
                                            val hint = event.body.takeIf {
                                                it.contains('.') && !it.startsWith("mxc://")
                                            }
                                            service.port.downloadAttachmentToCache(att, hint)
                                                .onSuccess { path -> openExternal(path, att.mime) }
                                                .onFailure { postError("Download failed") }
                                        }
                                    }
                                },
                                onForward = { eventIds ->
                                    backStack.add(Route.ForwardPicker(key.roomId, eventIds))
                                }
                            )
                        }

                        entry<Route.ForwardPicker> { key ->
                            val viewModel: ForwardPickerViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId, key.eventIds) }
                            )

                            ForwardPickerScreen(
                                viewModel = viewModel,
                                onBack = backStack::popBack,
                                onForwardComplete = { summary ->
                                    when {
                                        summary.totalRooms == 1 &&
                                            summary.successfulRooms == 1 &&
                                            summary.partialRooms == 0 &&
                                            summary.failedRooms == 0 -> {
                                            val room = summary.results.first()
                                            backStack.popUntil { it is Route.Rooms }
                                            backStack.add(Route.Room(room.roomId, room.roomName))
                                        }

                                        else -> {
                                            backStack.popBack()
                                            scope.launch {
                                                snackbarManager.show(summary.userMessage())
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        entry<Route.NotificationRules> {
                            NotificationRulesScreen(
                                matrixPort = service.port,
                                onBack = backStack::popBack
                            )
                        }

                        entry<Route.MediaCache> {
                            MediaCacheRoute(
                                onBack = backStack::popBack
                            )
                        }
                    }
                )
            }

            if (verState.sasFlowId != null && verState.sasPhase != null) {
                val showAcceptRequest =
                    verState.sasIncoming && verState.sasPhase == SasPhase.Requested

                val showContinue = when (verState.sasPhase) {
                    SasPhase.Ready, SasPhase.Started -> true
                    else -> false
                }

                SasDialog(
                    phase = verState.sasPhase,
                    emojis = verState.sasEmojis,
                    otherUser = verState.sasOtherUser ?: "",
                    otherDevice = verState.sasOtherDevice ?: "",
                    error = verState.sasError,
                    showAcceptRequest = showAcceptRequest,
                    showContinue = showContinue,
                    actionInFlight = verState.sasContinuePressed,
                    onAcceptOrContinue = verification::acceptOrContinue,
                    onConfirm = verification::confirm,
                    onCancel = verification::cancel
                )
            }
            GlobalCallOverlay(callManager, Modifier.fillMaxSize())
        }
    }
}
