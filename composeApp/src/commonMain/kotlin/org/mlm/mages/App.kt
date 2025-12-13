package org.mlm.mages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.BindDeepLinks
import org.mlm.mages.nav.Route
import org.mlm.mages.nav.loginEntryFadeMetadata
import org.mlm.mages.nav.navSavedStateConfiguration
import org.mlm.mages.nav.popUntil
import org.mlm.mages.nav.replaceTop
import org.mlm.mages.platform.BindLifecycle
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.rememberOpenBrowser
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.storage.loadString
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
import org.mlm.mages.ui.base.rememberSnackbarController
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.screens.DiscoverRoute
import org.mlm.mages.ui.screens.InvitesRoute
import org.mlm.mages.ui.screens.LoginScreen
import org.mlm.mages.ui.screens.RoomInfoRoute
import org.mlm.mages.ui.screens.RoomScreen
import org.mlm.mages.ui.screens.RoomsScreen
import org.mlm.mages.ui.screens.SecurityScreen
import org.mlm.mages.ui.screens.SpaceDetailScreen
import org.mlm.mages.ui.screens.SpaceSettingsScreen
import org.mlm.mages.ui.screens.SpacesScreen
import org.mlm.mages.ui.screens.ThreadRoute
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.viewmodel.DiscoverViewModel
import org.mlm.mages.ui.viewmodel.InvitesViewModel
import org.mlm.mages.ui.viewmodel.LoginViewModel
import org.mlm.mages.ui.viewmodel.RoomInfoViewModel
import org.mlm.mages.ui.viewmodel.RoomViewModel
import org.mlm.mages.ui.viewmodel.RoomsViewModel
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.mlm.mages.ui.viewmodel.SpaceDetailViewModel
import org.mlm.mages.ui.viewmodel.SpaceSettingsViewModel
import org.mlm.mages.ui.viewmodel.SpacesViewModel
import org.mlm.mages.ui.viewmodel.ThreadViewModel

@Composable
fun App(
    dataStore: DataStore<Preferences>,
    service: MatrixService,
    deepLinks: Flow<String>? = null
) {
    KoinApp(service = service, dataStore = dataStore) {
        AppContent(deepLinks = deepLinks)
    }
}

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun AppContent(
    deepLinks: Flow<String>?
) {
    val service: MatrixService = koinInject()
    val dataStore: DataStore<Preferences> = koinInject()

    MainTheme {
        val snackbar = rememberSnackbarController()
        val scope = rememberCoroutineScope()

        var showCreateRoom by remember { mutableStateOf(false) }
        var sessionEpoch by remember { mutableIntStateOf(0) }

        val initialRoute by produceState<Route?>(initialValue = null, service, dataStore) {
            val hs = loadString(dataStore, "homeserver")
            if (hs != null) {
                runCatching { service.init(hs) }
            }
            value = if (service.isLoggedIn()) Route.Rooms else Route.Login
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
        BindNotifications(service, dataStore)

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

        val openUrl = rememberOpenBrowser()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar.hostState) }
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
                                        snackbar.showError(event.message)
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
                            onOpenSpaces = { backStack.add(Route.Spaces) }
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
                                            snackbar.showError("Failed to create room")
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
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onOpenInfo = { backStack.add(Route.RoomInfo(key.roomId)) },
                            onNavigateToRoom = { roomId, name -> backStack.add(Route.Room(roomId, name)) },
                            onNavigateToThread = { roomId, eventId, roomName ->
                                backStack.add(Route.Thread(roomId, eventId, roomName))
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
                                        snackbar.showError(event.message)
                                    }
                                    is SecurityViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        SecurityScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
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
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        DiscoverRoute(
                            viewModel = viewModel,
                            onClose = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
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
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        InvitesRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
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
                                        snackbar.showError(event.message)
                                    }
                                    is RoomInfoViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        RoomInfoRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            onLeaveSuccess = { backStack.popUntil { it is Route.Rooms } }
                        )
                    }

                    entry<Route.Thread> { key ->
                        val viewModel: ThreadViewModel = koinViewModel(
                            parameters = { parametersOf(key.roomId, key.rootEventId) }
                        )

                        ThreadRoute(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                            snackbarController = snackbar
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
                                        snackbar.showError(event.message)
                                    }

                                    else -> {}
                                }
                            }
                        }

                        SpacesScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
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
                                        snackbar.showError(event.message)
                                    }
                                }
                            }
                        }

                        SpaceDetailScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
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
                                        snackbar.showError(event.message)
                                    }
                                    is SpaceSettingsViewModel.Event.ShowSuccess -> {
                                        snackbar.show(event.message)
                                    }
                                }
                            }
                        }

                        SpaceSettingsScreen(
                            viewModel = viewModel,
                            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
                        )
                    }
                }
            )
        }
    }
}