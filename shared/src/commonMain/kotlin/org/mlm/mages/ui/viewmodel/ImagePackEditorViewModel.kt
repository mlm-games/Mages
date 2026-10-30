package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.StickerInfo
import org.mlm.mages.matrix.ImagePackImageEntry
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.PackImageDraft
import org.mlm.mages.matrix.PackWrite
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.ui.ImagePackEditorUiState
import org.mlm.mages.ui.PackEditorEntry
import org.mlm.mages.ui.PendingPackImage
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res

/**
 * Editor for a room's image packs (spec v1.19 `m.room.image_pack`).
 *
 * Edits are staged in [ImagePackEditorUiState.packs] and only reach the server
 * on an explicit save, because a pack is written as a wholesale replacement of
 * its `images` map: there is no per-image write to interleave with an edit in
 * progress.
 */
class ImagePackEditorViewModel(
    private val service: MatrixService,
    private val roomId: String
) : BaseViewModel<ImagePackEditorUiState>(ImagePackEditorUiState()) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Editor previews resolve through the same homeserver thumbnail path the picker uses. */
    private val previewPathByMxc = mutableMapOf<String, String>()
    private val previewLock = Mutex()
    private var nextLocalId = 0L

    private companion object {
        /** Spec v1.19 shortcode grammar: `[a-zA-Z0-9-_]+`. */
        val SHORTCODE_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
    }

    init {
        load()
    }

    private fun load() {
        launch {
            val canEdit = runSafe { service.port.canEditImagePacks(roomId) } ?: false
            val packs = runSafe { service.port.listImagePacks(roomId) }.orEmpty()
            updateState {
                copy(
                    canEdit = canEdit,
                    // Only packs this room defines are editable here; the ones
                    // reached through `m.image_pack.rooms` live in other rooms.
                    packs = packs.filter { it.sourceRoom == roomId }.map(::entryFrom),
                    isLoading = false,
                    hasUnsavedChanges = false
                )
            }
        }
    }

    private fun reload() = load()

    private fun entryFrom(pack: ImagePackSummary) = PackEditorEntry(
        stateKey = pack.stateKey,
        displayName = pack.displayName ?: "",
        usage = pack.usage,
        images = pack.images,
        isEnabledGlobally = pack.isGlobal
    )

    /**
     * Resolves a preview path for a pack image, preferring the thumbnail the
     * pack author declared. Cached per media URI because the grid re-resolves
     * on every recomposition.
     */
    suspend fun packImagePreview(thumbnailMxcUri: String?, mxcUrl: String): String? =
        packImagePreview(thumbnailMxcUri, mxcUrl, null)

    suspend fun packImagePreview(
        thumbnailMxcUri: String?,
        mxcUrl: String,
        mime: String?,
    ): String? {
        val tgs = isTgsSticker(mime, mxcUrl)
        val key = if (tgs) mxcUrl else thumbnailMxcUri ?: mxcUrl
        previewLock.withLock { previewPathByMxc[key] }?.let { return it }
        val path = if (tgs) {
            service.downloadStickerToCache(StickerInfo(mxcUri = key, mime = mime)).getOrNull()
        } else {
            service.port.packImageToCache(key, 128, 128).getOrNull()
        } ?: return null
        previewLock.withLock { previewPathByMxc[key] = path }
        return path
    }

    /**
     * Stages newly picked images into [targetIndex], or into the first pack that
     * already exists when that is null, creating a pack if the room has none.
     *
     * Shortcodes are resolved in one batch call so that several files whose
     * names slugify to the same base cannot all be assigned the same shortcode.
     */
    fun addImages(targetIndex: Int?, paths: List<Pair<String, String>>) {
        if (paths.isEmpty()) return
        launch {
            val staged = paths.map { (path, mime) ->
                PendingPackImage(localId = "pending_${nextLocalId++}", path = path, mime = mime)
            }
            val bases = staged.map { fileStem(it.path) }
            val taken = currentState.packs.flatMap { pack ->
                pack.images.map(ImagePackImageEntry::shortcode) +
                    pack.pendingImages.map(PendingPackImage::shortcode)
            }
            val resolved = service.port.suggestImageShortcodes(bases, taken)
            val withShortcodes = staged.mapIndexed { index, image ->
                image.copy(shortcode = resolved.getOrElse(index) { "" })
            }

            var landed: Int? = null
            updateState {
                val packs = packs.toMutableList()
                val index = targetIndex?.takeIf { it in packs.indices }
                    ?: packs.indexOfFirst { !it.isNew }.takeIf { it >= 0 }
                    ?: packs.size
                if (index >= packs.size) {
                    packs.add(PackEditorEntry(pendingImages = withShortcodes))
                } else {
                    val existing = packs[index]
                    packs[index] = existing.copy(
                        pendingImages = existing.pendingImages + withShortcodes
                    )
                }
                landed = index
                copy(packs = packs, hasUnsavedChanges = true)
            }
            // A pack this pick created has no name yet, and its state key is
            // derived from one, so say so instead of leaving Save to fail.
            if (landed != null && currentState.packs.getOrNull(landed!!)?.isNew == true) {
                _events.send(Event.ShowSuccess(getString(Res.string.give_this_pack_a_name_then_save)))
            }
        }
    }

    private fun fileStem(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    fun updatePackName(index: Int, name: String) = mutate(index) {
        it.copy(displayName = name)
    }

    fun updatePackUsage(index: Int, usage: List<String>) = mutate(index) {
        it.copy(usage = usage)
    }

    /**
     * Adds or removes the pack from `m.image_pack.rooms`, which is what makes
     * its images available outside this room. Written straight through rather
     * than staged, since it is a different event from the pack itself.
     */
    fun setPackEnabledGlobally(index: Int, enabled: Boolean) {
        val pack = currentState.packs.getOrNull(index) ?: return
        if (pack.isNew || pack.stateKey in currentState.stateKeysBeingSaved) return
        updateState {
            copy(
                stateKeysBeingSaved = stateKeysBeingSaved + pack.stateKey,
                packs = packs.toMutableList().also {
                    it[index] = it[index].copy(isEnabledGlobally = enabled)
                }
            )
        }
        launch {
            // No reload here: the state event can land after the local list is
            // read back, which is what made the switch jump to its old value.
            service.port.setImagePackEnabled(roomId, pack.stateKey, enabled)
                .onSuccess { _events.send(Event.ShowSuccess(getString(Res.string.updated))) }
                .onFailure {
                    updateState {
                        copy(
                            packs = packs.toMutableList().also { packs ->
                                val i = packs.indexOfFirst { it.stateKey == pack.stateKey }
                                if (i >= 0) packs[i] = packs[i].copy(isEnabledGlobally = !enabled)
                            }
                        )
                    }
                    _events.send(Event.ShowError(getString(Res.string.could_not_update_this_pack)))
                }
            updateState { copy(stateKeysBeingSaved = stateKeysBeingSaved - pack.stateKey) }
        }
    }

    /**
     * Records a shortcode the user typed, marking it invalid when it falls
     * outside the spec grammar. The server revalidates, so this is only there
     * to report the problem before the save is attempted.
     */
    fun setPendingShortcode(packIndex: Int, localId: String, shortcode: String) =
        mutate(packIndex) { pack ->
            val taken = pack.images.map(ImagePackImageEntry::shortcode) +
                pack.pendingImages.filter { it.localId != localId }.map(PendingPackImage::shortcode)
            pack.copy(
                pendingImages = pack.pendingImages.map {
                    if (it.localId != localId) {
                        it
                    } else {
                        val error: StringResource? = when {
                            shortcode.isEmpty() -> Res.string.enter_a_shortcode
                            !SHORTCODE_PATTERN.matches(shortcode) ->
                                Res.string.use_letters_numbers_and_only
                            shortcode.length > 100 -> Res.string.shortcodes_can_be_at_most_100_characters
                            shortcode in taken -> Res.string.this_shortcode_is_already_used
                            else -> null
                        }
                        it.copy(shortcode = shortcode, shortcodeError = error)
                    }
                }
            )
        }

    fun removeImage(packIndex: Int, shortcode: String) = mutate(packIndex) { pack ->
        pack.copy(images = pack.images.filterNot { it.shortcode == shortcode })
    }

    fun removePendingImage(packIndex: Int, localId: String) = mutate(packIndex) { pack ->
        pack.copy(pendingImages = pack.pendingImages.filterNot { it.localId == localId })
    }

    fun addPack() {
        updateState {
            copy(packs = packs + PackEditorEntry(), hasUnsavedChanges = true)
        }
    }

    /**
     * Drops a pack from the editor and empties its `images` server-side, which
     * is the spec's removal: a state event cannot be deleted, only emptied.
     */
    fun removePack(index: Int) {
        val pack = currentState.packs.getOrNull(index) ?: return
        if (pack.isNew) {
            updateState {
                copy(packs = packs.filterIndexed { i, _ -> i != index }, hasUnsavedChanges = true)
            }
            return
        }
        updateState { copy(stateKeysBeingSaved = stateKeysBeingSaved + pack.stateKey) }
        launch {
            val result = service.port.removeImagePack(roomId, pack.stateKey)
            if (result.isFailure) {
                _events.send(Event.ShowError(getString(Res.string.could_not_remove_this_pack)))
            } else {
                load()
            }
            updateState { copy(stateKeysBeingSaved = stateKeysBeingSaved - pack.stateKey) }
        }
    }

    /**
     * Writes every pack that has an image, then reloads.
     *
     * A pack with no images is skipped rather than written: an empty `images`
     * map is how the spec expresses removal, so saving one would delete the
     * pack instead of creating an empty placeholder. Removing a pack is
     * [removePack]'s job, and it is the only thing that empties one.
     */
    fun save() {
        val dirty = currentState.packs.filter { it.imageCount > 0 }
        if (dirty.isEmpty()) {
            launch { _events.trySend(Event.ShowError(getString(Res.string.add_at_least_one_image_before_saving))) }
            return
        }
        // Clearing this at the end rather than recomputing means a global-toggle
        // that started mid-save is not silently dropped from the spinner set.
        val savingKeys = dirty.map { it.stateKey }.toSet()
        updateState {
            copy(stateKeysBeingSaved = stateKeysBeingSaved + savingKeys, isUploading = true)
        }
        launch {
            var step = 0
            val total = dirty.sumOf { it.pendingImages.size + 1 }.coerceAtLeast(1)
            val failures = mutableListOf<String>()

            for (pack in dirty) {
                val drafts = pack.images.map { image ->
                    PackImageDraft(
                        shortcode = image.shortcode,
                        mxcUrl = image.mxcUrl,
                        body = image.body,
                        infoJson = image.infoJson
                    )
                }.toMutableList()

                // An image whose shortcode is invalid, or whose upload failed,
                // is simply left out of the draft. That drops it from the pack
                // rather than failing the whole save, so a partial pack beats
                // no pack.
                for (pending in pack.pendingImages) {
                    if (pending.shortcodeError != null) {
                        failures.add(pending.shortcode.ifBlank { getString(Res.string.an_image) })
                    } else {
                        service.port.uploadPackImage(pending.path, pending.mime)
                            .onSuccess { uploaded ->
                                drafts.add(
                                    PackImageDraft(
                                        shortcode = pending.shortcode,
                                        mxcUrl = uploaded.mxcUrl,
                                        infoJson = uploaded.infoJson
                                    )
                                )
                            }
                            .onFailure { failures.add(pending.shortcode.ifBlank { getString(Res.string.an_image) }) }
                    }
                    step++
                    updateState { copy(uploadProgress = step.toFloat() / total) }
                }

                if (pack.displayName.isBlank()) {
                    // A pack's state key is derived from its name, so there is
                    // nothing sensible to file it under.
                    failures.add(getString(Res.string.an_unnamed_pack))
                    step++
                    continue
                }

                service.port.saveImagePack(
                    roomId,
                    PackWrite(
                        stateKey = pack.stateKey,
                        displayName = pack.displayName.trim(),
                        usage = pack.usage,
                        images = drafts
                    )
                ).onFailure { failures.add(pack.displayName) }
                step++
                updateState { copy(uploadProgress = step.toFloat() / total) }
            }

            updateState {
                copy(
                    isUploading = false,
                    uploadProgress = 0f,
                    stateKeysBeingSaved = stateKeysBeingSaved - savingKeys
                )
            }
            _events.send(
                if (failures.isEmpty()) Event.ShowSuccess(getString(Res.string.image_packs_saved))
                else Event.ShowError(getString(Res.string.could_not_save_images, failures.joinToString(", ")))
            )
            load()
        }
    }

    private fun mutate(index: Int, transform: (PackEditorEntry) -> PackEditorEntry) {
        updateState {
            if (index !in packs.indices) return@updateState this
            copy(
                packs = packs.toMutableList().also { it[index] = transform(it[index]) },
                hasUnsavedChanges = true
            )
        }
    }

    /** Re-reads from the server, discarding staged edits. */
    fun discardChanges() {
        updateState { copy(isLoading = true) }
        reload()
    }
}
