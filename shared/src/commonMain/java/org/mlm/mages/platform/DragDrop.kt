package org.mlm.mages.platform

import androidx.compose.ui.Modifier

/**
 * Adds file drag&drop support.
 *
 * onDrop receives a list of *local filesystem paths*.
 * - Desktop: real file paths from the OS
 * - Android: content URIs are copied to cacheDir and returned as file paths
 */
expect fun Modifier.fileDrop(
    enabled: Boolean,
    onDragEnter: () -> Unit,
    onDragExit: () -> Unit,
    onDrop: (List<String>) -> Unit
): Modifier