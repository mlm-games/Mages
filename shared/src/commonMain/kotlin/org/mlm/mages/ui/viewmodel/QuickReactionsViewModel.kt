package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.QuickReactionsUiState

class QuickReactionsViewModel(
    private val service: MatrixService,
) : BaseViewModel<QuickReactionsUiState>(QuickReactionsUiState()) {

    private val previewPaths = mutableMapOf<String, String?>()
    private val previewLock = Mutex()

    init {
        launch {
            val packs = runSafe { service.port.listAllImagePacks(refresh = false) }.orEmpty()
            updateState {
                copy(
                    packs = packs.filter { it.images.isNotEmpty() },
                    isLoading = false,
                )
            }
        }
    }

    suspend fun preview(mxcUrl: String): String? {
        previewLock.withLock {
            if (previewPaths.containsKey(mxcUrl)) return previewPaths[mxcUrl]
        }
        val path = runSafe { service.port.packImageToCache(mxcUrl, 128, 128) }
        previewLock.withLock { previewPaths[mxcUrl] = path }
        return path
    }
}
