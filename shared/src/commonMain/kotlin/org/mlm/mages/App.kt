package org.mlm.mages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.calls.CallManager
import org.mlm.mages.calls.CALL_END_GRACE_MS
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.answerIncomingCall
import org.mlm.mages.calls.declineIncomingCall
import org.mlm.mages.matrix.Presence
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.accountDataSettingsSync
import org.mlm.mages.matrix.MatrixPort.CallDeclineObserver
import org.mlm.mages.matrix.RoomCallState
import org.mlm.mages.matrix.MatrixPort.RoomCallStateObserver
import org.mlm.mages.nav.*
import org.mlm.mages.platform.BindAppLock
import org.mlm.mages.platform.BindLifecycle
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.BindScreenSecurity
import org.mlm.mages.platform.LocalAppLocale
import org.mlm.mages.platform.createAppLockController
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import org.mlm.mages.platform.shouldRequestLocalNetworkPermission
import org.mlm.mages.ui.components.AppLockScreen
import org.mlm.mages.ui.components.LocalNetworkPermissionDialogHost
import org.mlm.mages.ui.components.rememberLocalNetworkPermissionGate
import org.mlm.mages.platform.ProvideAppLocale
import org.mlm.mages.platform.platformEmbeddedElementCallParentUrlOrNull
import org.mlm.mages.platform.platformEmbeddedElementCallUrlOrNull
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberQuitApp
import org.mlm.mages.platform.toTransferItem
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.PresenceMode
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.settings.appLanguageTagOrNull
import org.mlm.mages.settings.toSeconds
import org.mlm.mages.ui.GlobalCallOverlay
import org.mlm.mages.ui.IncomingCallOverlay
import org.mlm.mages.ui.OngoingCallBanner
import org.mlm.mages.ui.animation.forwardTransition
import org.mlm.mages.ui.animation.popTransition
import org.mlm.mages.ui.components.dialogs.SasDialog
import org.mlm.mages.ui.components.sheets.AccountSwitcherSheet
import org.mlm.mages.ui.components.sheets.CreateRoomSheet
import org.mlm.mages.ui.components.sheets.StartChatSheet
import org.mlm.mages.ui.components.snackbar.LauncherSnackbarHost
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.screens.*
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import org.mlm.mages.ui.components.AttachmentSourceKind
import org.mlm.mages.ui.components.toMagesAttachment
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
    val incomingCalls: IncomingCallTracker = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())

    if (currentPlatform == SettingPlatform.WEB || currentPlatform == SettingPlatform.ANDROID) {
        BindNotifications(service = service, settingsRepository = settingsRepository)
    } //NOTE: web notifs , causes duplicate notifs on desktop if used there too (due to it being called on DesktopAppContent already).

    var initDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val proxyUrl = if (settings.proxyEnabled) settings.proxyUrl.takeIf { it.isNotBlank() } else null
        runCatching { service.initFromDisk(proxyUrl) }
        val oauthCompleted = runCatching { service.port.maybeFinishOauthRedirect() }.getOrDefault(false)
        if (oauthCompleted) {
            runCatching { service.initFromDisk(proxyUrl) }
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

    val backStack: NavBackStack<NavKey> =
        rememberNavBackStack(navSavedStateConfiguration, initialRoute)

    val isDark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val widgetTheme = if (isDark) "dark" else "light"
    val elementCallUrl =
        settings.elementCallUrl.trim().ifBlank { platformEmbeddedElementCallUrlOrNull() }
    val parentCallUrl = platformEmbeddedElementCallParentUrlOrNull()

    // Must live outside ProvideAppLocale: its key(languageTag) disposes the subtree.
    val navEntryViewModelStore = rememberViewModelStoreProvider()

    ProvideAppLocale(settings.appLanguageTagOrNull()) {
        MainTheme(
            darkTheme = isDark,
            dynamicColors = settings.dynamicColors,
            oledBlack = settings.oledBlack
        ) {
            val languageTag = LocalAppLocale.current
            val scope = rememberCoroutineScope()
            var sessionEpoch by remember { mutableIntStateOf(0) }
            var showStartChat by remember { mutableStateOf(false) }
            var showCreateRoom by remember { mutableStateOf(false) }
            var showAccountSwitcher by remember { mutableStateOf(false) }
            val accounts by accountStore.accounts.collectAsState()
            val activeAccountId by accountStore.activeAccountId.collectAsState()

            val localDeepLinks = remember {
                MutableSharedFlow<DeepLinkAction>(
                    extraBufferCapacity = 8
                )
            }
            val allDeepLinks = remember(deepLinks) {
                merge(deepLinks ?: emptyFlow(), localDeepLinks)
            }

            BindDeepLinks(
                backStack,
                allDeepLinks,
                callManager,
                widgetTheme,
                languageTag,
                elementCallUrl,
                parentCallUrl,
                onRequestVideoCallPermissions,
                onRequestVoiceCallPermissions,
            )

            BindLifecycle(service, resetSyncState = true)

            val appLockController = remember { createAppLockController() }
            val appLockTimeoutSeconds = settings.appLockTimeout.toSeconds()
            if (currentPlatform == SettingPlatform.ANDROID) {
                BindAppLock(appLockController, settings.appLockEnabled, appLockTimeoutSeconds)
                BindScreenSecurity(settings.screenSecurityEnabled)
            }
            val isAppLocked by appLockController.isLocked.collectAsState()
            var previousAppLockEnabled by remember { mutableStateOf(settings.appLockEnabled) }
            var suppressNextDisablePrompt by remember { mutableStateOf(false) }
            LaunchedEffect(settings.appLockEnabled) {
                if (settings.appLockEnabled == previousAppLockEnabled) return@LaunchedEffect
                val wasEnabled = previousAppLockEnabled
                val nowEnabled = settings.appLockEnabled
                previousAppLockEnabled = nowEnabled
                if (nowEnabled && !wasEnabled) {
                    if (!appLockController.isAvailable) {
                        snackbarManager.showError(getString(Res.string.set_a_screen_lock_in_system_settings_first))
                        suppressNextDisablePrompt = true
                        settingsRepository.update { it.copy(appLockEnabled = false) }
                    }
                } else if (!nowEnabled && wasEnabled) {
                    if (suppressNextDisablePrompt) {
                        suppressNextDisablePrompt = false
                        return@LaunchedEffect
                    }
                    appLockController.requestUnlock { success ->
                        if (!success) {
                            scope.launch {
                                snackbarManager.showError(getString(Res.string.authentication_required_to_disable_app_lock))
                                previousAppLockEnabled = true
                                settingsRepository.update { it.copy(appLockEnabled = true) }
                            }
                        }
                    }
                }
            }

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

            // Held until an account is available.
            LaunchedEffect(Unit) {
                PendingDeepLinks.links.collect { raw ->
                    service.activeAccount.first { it != null }

                    val action = resolveDeepLink(service, raw)
                    if (action == null) {
                        snackbarManager.showError(getString(Res.string.could_not_open_link, raw))
                    } else {
                        localDeepLinks.emit(action)
                    }
                }
            }

            // Presence is tracked and pushed on its own. applySyncPresence sets the status
            // flag to false, which is what keeps a presence change from rewriting the status
            // message below.
            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                val port = service.portOrNull ?: return@LaunchedEffect
                settingsRepository.flow
                    .map { it.presence }
                    .distinctUntilChanged()
                    .collect { mode -> port.applySyncPresence(mode.toPresence()) }
            }

            // The CS API carries presence and status in one request, so this supplies the
            // current presence to fill that field in. Only the status decides when to send, so
            // a presence change does not rewrite the status. The status is read back once up
            // front: pushing the default empty setting before that read would clear whatever
            // the server already has.
            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                val port = service.portOrNull ?: return@LaunchedEffect
                val current = port.whoami()?.let { port.getPresence(it) }
                if (current != null && settingsRepository.get<String>("statusMessage").isNullOrBlank()) {
                    settingsRepository.update { it.copy(statusMessage = current.second.orEmpty()) }
                }
                var sent = current?.second

                settingsRepository.flow
                    .map { it.presence to it.statusMessage }
                    .distinctUntilChanged { old, new -> old.second == new.second }
                    .collect { (mode, status) ->
                        val text = status.ifBlank { null }
                        if (text == sent) return@collect
                        if (text != null && text.encodeToByteArray().size > STATUS_MESSAGE_MAX_BYTES) {
                            Logger.w { "Status message over $STATUS_MESSAGE_MAX_BYTES bytes, not sending" }
                            return@collect
                        }
                        runCatching { port.setPresence(mode.toPresence(), text) }
                            .onSuccess { sent = text }
                    }
            }

            // MSC4278 media previews and MSC4380 invite blocking. The sync adopts the account
            // data preference if another client ever set one, then keeps account data in step
            // with the local setting. There is no account data change notification, so a change
            // made elsewhere is picked up the next time this account is opened.
            val remoteSettings = remember { accountDataSettingsSync(settingsRepository) { service.port } }
            val remoteStates by remoteSettings.states.collectAsState()
            LaunchedEffect(activeId) {
                if (activeId == null || !service.isLoggedInSuspend()) return@LaunchedEffect
                remoteSettings.attach(this)
                awaitCancellation()
            }
            DisposableEffect(activeId) {
                onDispose { remoteSettings.detach() }
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
                            message = getString(Res.string.sending_paused),
                            actionLabel = getString(Res.string.resume),
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
            val appLocked = isAppLocked && settings.appLockEnabled
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    snackbarHost = {
                        LauncherSnackbarHost(hostState = snackbarHostState, manager = snackbarManager)
                    }
                ) { _ ->
                    // The picker lives here because it needs a coroutine to turn
                    // a picked file into a resolvable path; the editor only ever
                    // asks for it to be launched and hands over a callback.
                    val packPickScope = rememberCoroutineScope()
                    var pendingPackPick by remember {
                        mutableStateOf<((List<Pair<String, String>>) -> Unit)?>(null)
                    }
                    val packImagePicker = rememberFilePickerLauncher(
                        mode = FileKitMode.Multiple(),
                        type = FileKitType.Image
                    ) { files ->
                        val onPicked = pendingPackPick ?: return@rememberFilePickerLauncher
                        val picked = files.orEmpty()
                        if (picked.isEmpty()) return@rememberFilePickerLauncher
                        packPickScope.launch {
                            onPicked(
                                picked.map { file ->
                                    // On web a picked file is staged as a blob rather
                                    // than a path on disk, so resolve it here.
                                    val attachment = file.toTransferItem().toMagesAttachment(
                                        AttachmentSourceKind.LocalPath
                                    )
                                    attachment.path to (file.mimeType()?.toString() ?: "image/png")
                                }
                            )
                        }
                    }

                    NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(navEntryViewModelStore)
                    ),
                    transitionSpec = forwardTransition,
                    popTransitionSpec = popTransition,
                    predictivePopTransitionSpec = { _ -> popTransition.invoke(this) },
                    onBack = {
                        if (appLocked) return@NavDisplay
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

                            val roomsGate = rememberLocalNetworkPermissionGate()

                            LaunchedEffect(activeAccount?.homeserver) {
                                val hs = activeAccount?.homeserver ?: return@LaunchedEffect
                                roomsGate.runWithPermission(hs) { }
                            }

                            RoomsScreen(
                                viewModel = viewModel,
                                onOpenSecurity = { backStack.add(Route.Security) },
                                onOpenStartChat = { showStartChat = true },
                                onOpenSpaces = { backStack.add(Route.Spaces) },
                                onOpenSearch = { backStack.add(Route.Search) },
                            )

                            LocalNetworkPermissionDialogHost(roomsGate)

                            if (showStartChat) {
                                StartChatSheet(
                                    matrixPort = service.port,
                                    onDismiss = { showStartChat = false },
                                    onCreateRoom = { showCreateRoom = true },
                                    onOpenDirectory = { backStack.add(Route.Discover) }
                                )
                            }

                            if (showCreateRoom) {
                                CreateRoomSheet(
                                    matrixPort = service.port,
                                    onCreate = { name, topic, invitees, isPublic, roomAlias ->
                                        scope.launch {
                                            runCatching {
                                                service.port.createRoom(
                                                    name,
                                                    topic,
                                                    invitees,
                                                    isPublic,
                                                    roomAlias
                                                )
                                            }.onSuccess { roomId ->
                                                if (roomId == null) return@onSuccess
                                                showCreateRoom = false
                                                showStartChat = false
                                                backStack.add(Route.Room(roomId, name ?: roomId))
                                            }.onFailure { e ->
                                                snackbarManager.showError(
                                                    e.message ?: getString(Res.string.failed_to_create_room)
                                                )
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
                                onNavigateToThread = { roomId, eventId, roomName, focusedEventId ->
                                    backStack.add(Route.Thread(roomId, eventId, roomName, focusedEventId))
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
                                onOpenAccountSwitcher = { showAccountSwitcher = true },
                                remoteStates = remoteStates,
                            )

                            if (showAccountSwitcher) {
                                AccountSwitcherSheet(
                                    accounts = accounts,
                                    activeAccountId = activeAccountId,
                                    onSelectAccount = { account ->
                                        scope.launch {
                                            val result = service.switchAccount(account)
                                            if (result.isSuccess) {
                                                sessionEpoch++
                                                snackbarManager.show(getString(Res.string.switched_to_account, account.userId))
                                            } else {
                                                snackbarManager.showError(result.exceptionOrNull()?.message ?: getString(Res.string.failed_to_switch_account))
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

                                        is DiscoverViewModel.Event.ShowSuccess -> {
                                            snackbarManager.show(event.message)
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
                                onOpenMediaGallery = { backStack.add(Route.MediaGallery(key.roomId)) },
                                onOpenImagePackEditor = { backStack.add(Route.ImagePackEditor(key.roomId)) },
                                onOpenSpace = { spaceId ->
                                    val name = viewModel.state.value.parentSpaces
                                        .firstOrNull { it.spaceId == spaceId }?.name.orEmpty()
                                    backStack.add(Route.SpaceDetail(spaceId, name))
                                }
                            )
                        }

                        entry<Route.ImagePackEditor> { key ->
                            val viewModel: ImagePackEditorViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId) }
                            )

                            LaunchedEffect(Unit) {
                                viewModel.events.collect { event ->
                                    when (event) {
                                        is ImagePackEditorViewModel.Event.ShowError ->
                                            postError(event.message)

                                        is ImagePackEditorViewModel.Event.ShowSuccess ->
                                            snackbarManager.show(event.message)
                                    }
                                }
                            }

                            ImagePackEditorRoute(
                                onBack = backStack::popBack,
                                onPickImages = { onPicked ->
                                    pendingPackPick = onPicked
                                    packImagePicker.launch()
                                },
                                viewModel = viewModel
                            )
                        }

                        entry<Route.Thread> { key ->
                            val viewModel: ThreadViewModel = koinViewModel(
                                parameters = { parametersOf(key.roomId, key.rootEventId, key.focusedEventId) }
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

                                        is ThreadViewModel.Event.NavigateToRoom -> {
                                            backStack.add(Route.Room(event.roomId, event.title))
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
                            val actionsViewModel: SpaceActionsViewModel = koinViewModel(
                                parameters = { parametersOf(key.spaceId) }
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

                                        is SpaceDetailViewModel.Event.ShowMessage -> {
                                            snackbarManager.show(event.message)
                                        }
                                    }
                                }
                            }

                            SpaceDetailScreen(
                                viewModel = viewModel,
                                actionsViewModel = actionsViewModel,
                                onBack = backStack::popBack,
                                onOpenSettings = { backStack.add(Route.SpaceSettings(key.spaceId)) }
                            )
                        }

                        entry<Route.SpaceSettings> { key ->
                            val viewModel: SpaceSettingsViewModel = koinViewModel(
                                parameters = { parametersOf(key.spaceId) }
                            )
                            val actionsViewModel: SpaceActionsViewModel = koinViewModel(
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
                                actionsViewModel = actionsViewModel,
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
                                                .onFailure { postError(getString(Res.string.download_failed)) }
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
                if (appLocked) {
                    AppLockScreen(
                        onUnlock = { appLockController.requestUnlock() }
                    )
                }
            }

            if (verState.sasPhase != null &&
                (verState.sasFlowId != null || verState.sasError != null)
            ) {
                val showAcceptRequest =
                    verState.sasIncoming && verState.sasPhase == SasPhase.Requested

                SasDialog(
                    phase = verState.sasPhase,
                    emojis = verState.sasEmojis,
                    otherUser = verState.sasOtherUser ?: "",
                    otherDevice = verState.sasOtherDevice ?: "",
                    error = verState.sasError,
                    showAcceptRequest = showAcceptRequest,
                    actionInFlight = verState.sasActionInFlight,
                    onAccept = verification::accept,
                    onConfirm = verification::confirm,
                    onCancel = verification::cancel
                )
            }
            val externalCallHost by callManager.externalHost.collectAsState()
            if (!externalCallHost) {
                GlobalCallOverlay(callManager, Modifier.fillMaxSize())
            }

            Box(Modifier.fillMaxSize()) {
                val invites by incomingCalls.invites.collectAsState()
                val canShowIncomingOverlay =
                    currentPlatform == SettingPlatform.WEB || currentPlatform == SettingPlatform.JVM
            LaunchedEffect(callState?.roomId) {
                callState?.roomId?.let { incomingCalls.clearForRoom(it) }
            }
            LaunchedEffect(invites.map { it.roomId to it.eventId }) {
                val reconcilerScope = this
                val me = runCatching { service.portOrNull?.whoami() }.getOrNull()
                if (me == null) {
                    Logger.w { "Call reconciler: whoami() failed, own-decline dismissal disabled" }
                }
                val seenActive = mutableSetOf<String>()
                val lastActive = mutableMapOf<String, Boolean>()
                invites.groupBy { it.roomId }.forEach { (roomId, roomInvites) ->
                    launch {
                        val token = runCatching {
                            service.portOrNull?.observeRoomCallState(
                                roomId,
                                object : RoomCallStateObserver {
                                    override fun onUpdate(state: RoomCallState) {
                                        if (me != null && state.activeParticipants.contains(me)) {
                                            incomingCalls.clearForRoom(roomId)
                                        } else if (state.hasActiveCall) {
                                            seenActive += roomId
                                            lastActive[roomId] = true
                                        } else {
                                            seenActive += roomId
                                            lastActive[roomId] = false
                                            reconcilerScope.launch {
                                                delay(CALL_END_GRACE_MS)
                                                if (lastActive[roomId] == false && roomId in seenActive) {
                                                    seenActive -= roomId
                                                    incomingCalls.clearForRoom(roomId)
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }.onFailure { e ->
                            Logger.w { "Call reconciler: observeRoomCallState($roomId) failed: ${e.message}" }
                        }.getOrNull()
                        try {
                            awaitCancellation()
                        } finally {
                            token?.let { service.portOrNull?.unobserveRoomCallState(it) }
                        }
                    }
                    roomInvites.forEach { invite ->
                        launch {
                            val token = runCatching {
                                service.portOrNull?.observeCallDecline(
                                    roomId,
                                    invite.eventId,
                                    object : CallDeclineObserver {
                                        override fun onDecline(declinerUserId: String) {
                                            incomingCalls.dismiss(roomId, invite.eventId)
                                        }
                                    }
                                )
                            }.onFailure { e ->
                                Logger.w { "Call reconciler: observeCallDecline($roomId) failed: ${e.message}" }
                            }.getOrNull()
                            try {
                                awaitCancellation()
                            } finally {
                                token?.let { service.portOrNull?.unobserveCallDecline(it) }
                            }
                        }
                    }
                }
            }
            val ringing = invites.firstOrNull()
            if (ringing != null && canShowIncomingOverlay && !appLocked) {
                IncomingCallOverlay(
                    call = ringing,
                    moreCount = (invites.size - 1).coerceAtLeast(0),
                    onAnswer = {
                        answerIncomingCall(incomingCalls, ringing) { action ->
                            localDeepLinks.tryEmit(action)
                        }
                    },
                    onDecline = {
                        scope.launch {
                            declineIncomingCall(service.portOrNull, incomingCalls, ringing)
                        }
                    },
                    onTimeout = {
                        incomingCalls.dismiss(ringing.roomId, ringing.eventId)
                    }
                )
            }

            val ongoing = callState
            if (ongoing != null && ongoing.minimized && !appLocked) {
                OngoingCallBanner(
                    roomName = ongoing.roomName,
                    startedAtMs = ongoing.startedAtMs,
                    onTap = {
                        callManager.setMinimized(false)
                        val top = backStack.lastOrNull()
                        if (top !is Route.Room || top.roomId != ongoing.roomId) {
                            backStack.add(Route.Room(ongoing.roomId, ongoing.roomName))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

private const val STATUS_MESSAGE_MAX_BYTES = 255

internal fun PresenceMode.toPresence(): Presence = when (this) {
    PresenceMode.Online -> Presence.Online
    PresenceMode.Offline -> Presence.Offline
    PresenceMode.Unavailable -> Presence.Unavailable
}
