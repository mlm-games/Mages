package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.MatrixService
import org.mlm.mages.StickerInfo
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.ui.QuickReactionsUiState

class QuickReactionsViewModel(
    private val service: MatrixService,
) : BaseViewModel<QuickReactionsUiState>(QuickReactionsUiState()) {

    private val previewPaths = mutableMapOf<String, String>()
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

    suspend fun preview(mxcUrl: String, mime: String? = null, full: Boolean = false): String? {
        val animated = full || isTgsSticker(mime, mxcUrl)
        val key = if (animated) "full:$mxcUrl" else mxcUrl
        previewLock.withLock { previewPaths[key]?.let { return it } }
        val path = if (animated) {
            runSafe { service.downloadStickerToCache(StickerInfo(mxcUri = mxcUrl, mime = mime)) }
                ?.getOrNull()
        } else {
            runSafe { service.port.packImageToCache(mxcUrl, 128, 128).getOrNull() }
        }
        if (path != null) previewLock.withLock { previewPaths[key] = path }
        return path
    }
}
