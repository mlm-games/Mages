package org.mlm.mages.di

import androidx.compose.material3.SnackbarHostState
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mlm.mages.MatrixService
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.viewmodel.*
import org.mlm.mages.verification.VerificationCoordinator

val coreModule = module {
    single { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    single { SnackbarHostState() }
    single { SnackbarManager() }
}

val accountModule = module {
    single { AccountStore(get(), get()) }
    single { MatrixClients(get()) }
    single { MatrixService(get()) }
    single { VerificationCoordinator(get()) }
}

val viewModelModule = module {
    viewModel { (roomId: String, roomName: String) ->
        RoomViewModel(
            service = get(),
            roomId = roomId,
            roomName = roomName
        )
    }

    viewModel { RoomsViewModel(get()) }

    viewModel { SecurityViewModel(get(), get(), get()) }

    viewModel { LoginViewModel(get(), get()) }

    viewModel { SpacesViewModel(get()) }

    viewModel { (spaceId: String, spaceName: String) ->
        SpaceDetailViewModel(
            service = get(),
            spaceId = spaceId,
            spaceName = spaceName
        )
    }

    viewModel { (spaceId: String) ->
        SpaceSettingsViewModel(
            service = get(),
            spaceId = spaceId
        )
    }

    viewModel { (roomId: String, rootEventId: String) ->
        ThreadViewModel(
            service = get(),
            roomId = roomId,
            rootEventId = rootEventId
        )
    }

    viewModel { (roomId: String) ->
        RoomInfoViewModel(
            service = get(),
            roomId = roomId
        )
    }

    viewModel { DiscoverViewModel(get()) }

    viewModel { InvitesViewModel(get()) }

    viewModel { (scopedRoomId: String?, scopedRoomName: String?) ->
        SearchViewModel(
            service = get(),
            scopedRoomId = scopedRoomId,
            scopedRoomName = scopedRoomName
        )
    }

    viewModel { params -> MediaGalleryViewModel(get(), params.get()) }

    viewModel { params ->
        ForwardPickerViewModel(
            service = get(),
            sourceRoomId = params.get(),
            eventIds = params.get()
        )
    }

    viewModel { AccountsViewModel(get(), get()) }
}

fun appModules(settingsRepository: SettingsRepository<AppSettings>) = listOf(
    module { single { settingsRepository } },
    coreModule,
    accountModule,
    viewModelModule
)