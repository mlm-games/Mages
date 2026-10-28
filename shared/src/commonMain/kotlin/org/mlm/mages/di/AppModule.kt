package org.mlm.mages.di

import androidx.compose.material3.SnackbarHostState
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mlm.mages.MatrixService
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.bookmarks.BookmarksStore
import org.mlm.mages.calls.CallManager
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.emoji.RecentEmojiStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.viewmodel.*
import org.mlm.mages.wallpaper.WallpaperStore
import org.mlm.mages.verification.VerificationCoordinator

val coreModule = module {
    single { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    single { SnackbarHostState() }
    single { SnackbarManager() }
    single { CallManager(get()) }
    single { IncomingCallTracker() }
}

val accountModule = module {
    single { AccountStore(get(), get()) }
    single { MatrixClients(get(), get()) }
    single { MatrixService(get(), get()) }
    single { VerificationCoordinator(get()) }
    single { RecentEmojiStore { get<MatrixClients>().portOrNull ?: error("no active account") } }
    single { BookmarksStore(get(), { get<MatrixClients>().portOrNull }) }
    single { WallpaperStore(get(), { get<MatrixClients>().portOrNull }) }

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

    viewModel { (spaceId: String) ->
        SpaceActionsViewModel(
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

    viewModel { (roomId: String, rootEventId: String, focusedEventId: String?) ->
        ThreadViewModel(
            service = get(),
            roomId = roomId,
            rootEventId = rootEventId,
            focusedEventId = focusedEventId
        )
    }

    viewModel { (roomId: String) ->
        RoomInfoViewModel(
            service = get(),
            roomId = roomId
        )
    }

    viewModel { DiscoverViewModel(get()) }

    viewModel { (scopedRoomId: String?, scopedRoomName: String?) ->
        SearchViewModel(
            service = get(),
            settingsRepository = get(),
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

    viewModel { MediaCacheViewModel(get()) }

    viewModel { params -> ImagePackEditorViewModel(get(), params.get()) }

    viewModel { QuickReactionsViewModel(get()) }
}

fun appModules(settingsRepository: SettingsRepository<AppSettings>) = listOf(
    module { single { settingsRepository } },
    coreModule,
    accountModule,
    viewModelModule
)