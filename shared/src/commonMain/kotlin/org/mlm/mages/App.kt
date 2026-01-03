package org.mlm.mages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.di.KoinApp
import org.mlm.mages.matrix.Presence
import org.mlm.mages.nav.BindDeepLinks
import org.mlm.mages.nav.Route
import org.mlm.mages.nav.loginEntryFadeMetadata
import org.mlm.mages.nav.navSavedStateConfiguration
import org.mlm.mages.nav.popUntil
import org.mlm.mages.nav.replaceTop
import org.mlm.mages.platform.BindLifecycle
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.components.snackbar.LauncherSnackbarHost
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.screens.DiscoverRoute
import org.mlm.mages.ui.screens.ForwardPickerScreen
import org.mlm.mages.ui.screens.InvitesRoute
import org.mlm.mages.ui.screens.LoginScreen
import org.mlm.mages.ui.screens.MediaGalleryScreen
import org.mlm.mages.ui.screens.RoomInfoRoute
import org.mlm.mages.ui.screens.RoomScreen
import org.mlm.mages.ui.screens.RoomsScreen
import org.mlm.mages.ui.screens.SearchScreen
import org.mlm.mages.ui.screens.SecurityScreen
import org.mlm.mages.ui.screens.SpaceDetailScreen
import org.mlm.mages.ui.screens.SpaceSettingsScreen
import org.mlm.mages.ui.screens.SpacesScreen
import org.mlm.mages.ui.screens.ThreadRoute
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.DiscoverViewModel
import org.mlm.mages.ui.viewmodel.ForwardPickerViewModel
import org.mlm.mages.ui.viewmodel.InvitesViewModel
import org.mlm.mages.ui.viewmodel.LoginViewModel
import org.mlm.mages.ui.viewmodel.MediaGalleryViewModel
import org.mlm.mages.ui.viewmodel.RoomInfoViewModel
import org.mlm.mages.ui.viewmodel.RoomViewModel
import org.mlm.mages.ui.viewmodel.RoomsViewModel
import org.mlm.mages.ui.viewmodel.SearchViewModel
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.mlm.mages.ui.viewmodel.SpaceDetailViewModel
import org.mlm.mages.ui.viewmodel.SpaceSettingsViewModel
import org.mlm.mages.ui.viewmodel.SpacesViewModel
import org.mlm.mages.ui.viewmodel.ThreadViewModel

val LocalMessageFontSize = staticCompositionLocalOf { 16f }

@Composable
fun App(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>,
    deepLinks: Flow<String>? = null
) {
    val settings by settingsRepository.flow.collectAsState(AppSettings())

    CompositionLocalProvider(LocalMessageFontSize provides settings.fontSize) {

        KoinApp(service, settingsRepository) {
            AppContent(deepLinks = deepLinks)
        }
    }
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun AppContent(
    deepLinks: Flow<String>?
) {
    val service: MatrixService = koinInject()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val snackbarManager: SnackbarManager = koinInject()
    val snackbarHostState: SnackbarHostState = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())

    LaunchedEffect(service) {
        settingsRepository.flow.collect { s ->
            if (!service.isLoggedIn()) return@collect
            runCatching { service.port.setPresence(Presence.entries[s.presence], null) }
        }
    }

    MainTheme(darkTheme = when (settings.themeMode) {
        ThemeMode.System.ordinal -> isSystemInDarkTheme()
        ThemeMode.Dark.ordinal -> true
        ThemeMode.Light.ordinal -> false
        else -> { isSystemInDarkTheme() }
    }) {
        val scope = rememberCoroutineScope()

        var showCreateRoom by remember { mutableStateOf(false) }
        var sessionEpoch by remember { mutableIntStateOf(0) }


        val initialRoute by produceState<Route?>(initialValue = null, service, settingsRepository) {
            val hs = settingsRepository.flow.first().homeserver
            if (hs.isNotBlank()) {
                runCatching { service.init(hs) }
            }
            value = if (service.isLoggedIn()) Route.Rooms else Route.Login
        }

        LaunchedEffect(service, initialRoute) {
            if (initialRoute != Route.Rooms) return@LaunchedEffect
            if (!service.isLoggedIn()) return@LaunchedEffect

            service.port.observeSends().collect { update ->
                if (update.txnId.isBlank() && update.error?.contains("send queue disabled") == true) {
                    snackbarManager.show(
                        message = "Sending paused",
                        actionLabel = "Resume",
                        duration = SnackbarDuration.Indefinite,
                        onAction = {
                            service.port.sendQueueSetEnabled(true)
                        }
                    )
                }
            }
        }

        if (initialRoute == null) {
            Surface {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            return@MainTheme
        }

        val backStack: NavBackStack<NavKey> =
            rememberNavBackStack(navSavedStateConfiguration, initialRoute!!)

        BindDeepLinks(backStack, deepLinks)
        BindLifecycle(service)
        BindNotifications(service, settingsRepository)

        LaunchedEffect(initialRoute) {
            if (initialRoute == Route.Rooms && service.isLoggedIn()) {
                sessionEpoch++
            }
        }

        LaunchedEffect(sessionEpoch) {
            if (service.isLoggedIn()) {
                service.startSupervisedSync()
            }
        }

        val uriHandler = LocalUriHandler.current

        val openUrl: (String) -> Boolean = remember(uriHandler) {
            { url ->
                try {
                    uriHandler.openUri(url)
                    true
                } catch (t: Throwable) {
                    t.printStackTrace()
                    false
                }
            }
        }

        Scaffold(
            snackbarHost = {
                LauncherSnackbarHost(
                    hostState = snackbarHostState,
                    manager = snackbarManager
                )
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
                    val blockBack = top == Route.Login || top == Route.Rooms
                    if (!blockBack && backStack.size > 1) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                },
                entryProvider = entryProvider {

                    entry<Route.Login>(metadata = loginEntryFadeMetadata()) {
                        val viewModel: LoginViewModel = koinViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.events.collect { event ->
                                when (event) {
                                    LoginViewModel.Event.LoginSuccess -> {
                                        sessionEpoch++
                                        backStack.replaceTop(Route.Rooms)
                                    }
                                }
                            }
                        }

                        LoginScreen(
                            viewModel = viewModel,
                            onSso = { viewModel.startSso(openUrl) }
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
                            onOpenSearch = { backStack.add(Route.Search) }
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
                            onStartCall = { viewModel.startCall() },
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
                            backStack = backStack
                        )
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
    }
}