package org.mlm.mages.platform

import androidx.compose.runtime.Composable

actual fun isLocalNetworkPermissionEnforced(): Boolean = false
actual fun hasLocalNetworkPermission(): Boolean = true
actual suspend fun shouldRequestLocalNetworkPermission(homeserverUrl: String): Boolean = false
actual fun openAppPermissionSettings() = Unit

@Composable
actual fun rememberLocalNetworkPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit = { onResult(true) }
