package org.mlm.mages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
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
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.ui.GlobalCallOverlay
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
import org.mlm.mages.ui.components.dialogs.SasDialog
import org.mlm.mages.ui.components.sheets.AccountSwitcherSheet
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.components.snackbar.LauncherSnackbarHost
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.screens.*
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.*
import org.mlm.mages.verification.VerificationCoordinator
import java.util.Locale

val LocalMessageFontSize = staticCompositionLocalOf { 16f }

@Composable
fun App(
    settingsRepository: SettingsRepository<AppSettings>,
    deepLinks: Flow<String>? = null
) {
    val settings by settingsRepository.flow.collectAsState(AppSettings())

    CompositionLocalProvider(LocalMessageFontSize provides settings.fontSize) {
        AppContent(deepLinks = deepLinks)
    }
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun AppContent(deepLinks: Flow<String>?) {
    val service: MatrixService = koinInject()
    val accountStore: AccountStore = koinInject()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val snackbarManager: SnackbarManager = koinInject()
    val snackbarHostState: SnackbarHostState = koinInject()
    val callManager: CallManager = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())

    var initDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { service.initFromDisk() }
        initDone = true
    }

    val activeAccount by service.activeAccount.collectAsState()
    val activeId = activeAccount?.id

    val verification: VerificationCoordinator = koinInject()
    val verState by verification.state.collectAsState()

    if (!initDone) {
        Surface(color = darkColorScheme().surface) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
        return
    }

    val initialRoute = remember(activeId) {
        if (activeId != null && service.isLoggedIn()) Route.Rooms else Route.Login
    }

    val isDark = when (settings.themeMode) {
        ThemeMode.System.ordinal -> isSystemInDarkTheme()
        ThemeMode.Dark.ordinal -> true
        ThemeMode.Light.ordinal -> false
        else -> isSystemInDarkTheme()
    }
    val widgetTheme = if (isDark) "dark" else "light"

    val languageTag = Locale.getDefault().toLanguageTag()

    MainTheme(
        darkTheme = isDark
    ) {
        val scope = rememberCoroutineScope()
        var sessionEpoch by remember { mutableIntStateOf(0) }
        var showCreateRoom by remember { mutableStateOf(false) }
        var showAccountSwitcher by remember { mutableStateOf(false) }
        val accounts by accountStore.accounts.collectAsState()
        val activeAccountId by accountStore.activeAccountId.collectAsState()

        val backStack: NavBackStack<NavKey> =
            rememberNavBackStack(navSavedStateConfiguration, initialRoute)

        BindDeepLinks(backStack, deepLinks)

        BindLifecycle(service)
        BindNotifications(service, settingsRepository)

        LaunchedEffect(activeId) {
            if (activeId == null || !service.isLoggedIn()) {
                backStack.clear()
                backStack.add(Route.Login)
                return@LaunchedEffect
            } else {
                // If account becomes active, go to Rooms
                if (backStack.lastOrNull() == Route.Login) {
                    backStack.replaceTop(Route.Rooms)
                }
            }
        }

        LaunchedEffect(activeId) {
            if (activeId == null || !service.isLoggedIn()) return@LaunchedEffect
            settingsRepository.flow.collect { s ->
                runCatching { service.port.setPresence(Presence.entries[s.presence], null) }
            }
        }

        LaunchedEffect(activeId) {
            service.resetSyncState()
            if (activeId == null || !service.isLoggedIn()) return@LaunchedEffect
            service.startSupervisedSync()
        }

        LaunchedEffect(activeId) {
            if (activeId == null || !service.isLoggedIn()) return@LaunchedEffect
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
                    val blockBack = isInitialLogin || isRoomsRoot
                    if (!blockBack && backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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
                                        snackbarManager.showError("$event.message")
                                    }
                                }
                            }
                        }

                        RoomsScreen(
                            viewModel = viewModel,
                            onOpenSecurity = { backStack.add(Route.Security) },
                            onOpenDiscover = { backStack.add(Route.Discover) },
                            onOpenInvites = { backStack.add(Route.Invites) },
                            onOpenCreateRoom = { showCreateRoom = true },
                            onOpenSpaces = { backStack.add(Route.Spaces) },
                            onOpenSearch = { backStack.add(Route.Search) },
                        )

                        if (showCreateRoom) {
                            CreateRoomSheet(
                                onCreate = { name, topic, invitees ->
                                    scope.launch {
                                        val roomId = service.port.createRoom(name, topic, invitees)
                                        if (roomId != null) {
                                            showCreateRoom = false
                                            backStack.add(Route.Room(roomId, name ?: roomId))
                                        } else {
                                            snackbarManager.show("Error: Failed to create room")
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
                            onNavigateToRoom = { roomId, name -> backStack.add(Route.Room(roomId, name)) },
                            onNavigateToThread = { roomId, eventId, roomName ->
                                backStack.add(Route.Thread(roomId, eventId, roomName))
                            },
                            onStartCall = {
                                viewModel.startCall(
                                    theme = widgetTheme,
                                    languageTag = languageTag,
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
                                        snackbarManager.show("Error: ${event.message}")
                                    }
                                    is SecurityViewModel.Event.ShowSuccess -> {
                                        snackbarManager.show(event.message)
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
                                        if (!service.isLoggedIn()) {
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
                                        snackbarManager.show("Error: $event.message")
                                    }
                                }
                            }
                        }

                        DiscoverRoute(
                            viewModel = viewModel,
                            onClose = backStack::popBack
                        )
                    }

                    entry<Route.Invites> {
                        val viewModel: InvitesViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    is InvitesViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is InvitesViewModel.Event.ShowError -> {
                                        snackbarManager.show("Error: $event.message")
                                    }
                                }
                            }
                        }

                        InvitesRoute(
                            viewModel = viewModel,
                            onBack = backStack::popBack
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
                                        snackbarManager.show("Error: $event.message")
                                    }
                                    is RoomInfoViewModel.Event.ShowSuccess -> {
                                        snackbarManager.show(event.message)
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
                                        backStack.add(Route.SpaceDetail(event.spaceId, event.name))
                                    }
                                    is SpacesViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is SpacesViewModel.Event.ShowError -> {
                                        snackbarManager.show("Error: $event.message")
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
                                        backStack.add(Route.SpaceDetail(event.spaceId, event.name))
                                    }
                                    is SpaceDetailViewModel.Event.OpenRoom -> {
                                        backStack.add(Route.Room(event.roomId, event.name))
                                    }
                                    is SpaceDetailViewModel.Event.ShowError -> {
                                        snackbarManager.show("Error: $event.message")
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
                                        snackbarManager.show("Error: $event.message")
                                    }
                                    is SpaceSettingsViewModel.Event.ShowSuccess -> {
                                        snackbarManager.show(event.message)
                                    }
                                }
                            }
                        }

                        SpaceSettingsScreen(
                            viewModel = viewModel,
                            onBack = backStack::popBack
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
                                        backStack.add(Route.Room(event.roomId, event.roomName, event.eventId))
                                    }
                                    is SearchViewModel.Event.ShowError -> {
                                        snackbarManager.showError(event.message)
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
                                        val hint = event.body.takeIf { it.contains('.') && !it.startsWith("mxc://") }
                                        service.port.downloadAttachmentToCache(att, hint)
                                            .onSuccess { path -> openExternal(path, att.mime) }
                                            .onFailure { snackbarManager.showError("Download failed") }
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
                            onForwardComplete = { roomId, roomName ->
                                backStack.popUntil { it is Route.Rooms }
                                backStack.add(Route.Room(roomId, roomName))
                            }
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