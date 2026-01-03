
package org.mlm.mages.di

import androidx.compose.material3.SnackbarHostState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mlm.mages.MatrixService
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.viewmodel.*

val appModule = module {

    single { SnackbarHostState() }
    single { SnackbarManager() }

    // Room
    viewModel { (roomId: String, roomName: String) ->
        RoomViewModel(
            service = get(),
            roomId = roomId,
            roomName = roomName
        )
    }

    // Rooms list
    viewModel {
        RoomsViewModel(
            get()
        )
    }

    // Security
    viewModel { SecurityViewModel(get(), get()) }

    // Login
    viewModel {
        LoginViewModel(
            get(),
            get()
        )
    }

    // Spaces
    viewModel {
        SpacesViewModel(
            get()
        )
    }

    // Space Detail
    viewModel { (spaceId: String, spaceName: String) ->
        SpaceDetailViewModel(
            service = get(),
            spaceId = spaceId,
            spaceName = spaceName
        )
    }

    // Space Settings
    viewModel { (spaceId: String) ->
        SpaceSettingsViewModel(
            service = get(),
            spaceId = spaceId
        )
    }

    // Thread
    viewModel { (roomId: String, rootEventId: String) ->
        ThreadViewModel(
            service = get(),
            roomId = roomId,
            rootEventId = rootEventId
        )
    }

    // Room Info
    viewModel { (roomId: String) ->
        RoomInfoViewModel(
            service = get(),
            roomId = roomId
        )
    }

    // Discover
    viewModel {
        DiscoverViewModel(
            get()
        )
    }

    // Invites
    viewModel {
        InvitesViewModel(
            get()
        )
    }

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
}

fun appModules(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>
) = listOf(
    module {
        single { service }
        single { settingsRepository }
    },
    appModule
)