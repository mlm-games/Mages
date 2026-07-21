package org.mlm.mages.platform

import androidx.compose.runtime.Composable

expect fun isLocalNetworkPermissionEnforced(): Boolean

expect fun hasLocalNetworkPermission(): Boolean

expect suspend fun shouldRequestLocalNetworkPermission(homeserverUrl: String): Boolean

expect fun openAppPermissionSettings()

@Composable
expect fun rememberLocalNetworkPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit
