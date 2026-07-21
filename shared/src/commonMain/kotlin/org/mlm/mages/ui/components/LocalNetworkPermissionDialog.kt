package org.mlm.mages.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.platform.hasLocalNetworkPermission
import org.mlm.mages.platform.openAppPermissionSettings
import org.mlm.mages.platform.rememberLocalNetworkPermissionRequester
import org.mlm.mages.platform.shouldRequestLocalNetworkPermission

enum class LocalNetworkDialogKind { None, Rationale, Settings }

class LocalNetworkPermissionGateState internal constructor(
    val dialog: LocalNetworkDialogKind,
    val runWithPermission: (homeserverUrl: String, action: () -> Unit) -> Unit,
    val onAllow: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun rememberLocalNetworkPermissionGate(): LocalNetworkPermissionGateState {
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dialog by remember { mutableStateOf(LocalNetworkDialogKind.None) }
    var permissionGeneration by remember { mutableIntStateOf(0) }
    var hasBeenAsked by remember { mutableStateOf(false) }

    val requestPermission = rememberLocalNetworkPermissionRequester { granted ->
        permissionGeneration++
        if (!granted) hasBeenAsked = true
        val action = pendingAction
        pendingAction = null
        dialog = LocalNetworkDialogKind.None
        if (granted) action?.invoke()
    }

    LaunchedEffect(permissionGeneration, dialog) {
        if (dialog != LocalNetworkDialogKind.None && hasLocalNetworkPermission()) {
            val action = pendingAction
            pendingAction = null
            dialog = LocalNetworkDialogKind.None
            action?.invoke()
        }
    }

    return remember(dialog, requestPermission, hasBeenAsked) {
        LocalNetworkPermissionGateState(
            dialog = dialog,
            runWithPermission = { url, action ->
                scope.launch {
                    if (!shouldRequestLocalNetworkPermission(url)) {
                        action()
                        return@launch
                    }
                    pendingAction = action
                    dialog = if (hasLocalNetworkPermission()) {
                        LocalNetworkDialogKind.None.also { action() }
                    } else if (hasBeenAsked) {
                        LocalNetworkDialogKind.Settings
                    } else {
                        LocalNetworkDialogKind.Rationale
                    }
                }
            },
            onAllow = {
                when (dialog) {
                    LocalNetworkDialogKind.Settings -> {
                        openAppPermissionSettings()
                    }
                    LocalNetworkDialogKind.Rationale -> {
                        hasBeenAsked = true
                        requestPermission()
                    }
                    else -> Unit
                }
            },
            onDismiss = {
                pendingAction = null
                dialog = LocalNetworkDialogKind.None
            },
        )
    }
}

@Composable
fun LocalNetworkPermissionDialogHost(
    state: LocalNetworkPermissionGateState,
    modifier: Modifier = Modifier,
) {
    when (state.dialog) {
        LocalNetworkDialogKind.None -> return
        LocalNetworkDialogKind.Rationale,
        LocalNetworkDialogKind.Settings -> {
            val confirmLabel = if (state.dialog == LocalNetworkDialogKind.Settings) {
                stringResource(Res.string.local_network_open_settings)
            } else {
                stringResource(Res.string.local_network_allow_access)
            }
            AlertDialog(
                onDismissRequest = state.onDismiss,
                title = { Text(stringResource(Res.string.local_network_permission_title)) },
                text = { Text(stringResource(Res.string.local_network_permission_subtitle)) },
                confirmButton = {
                    TextButton(onClick = {
                        if (state.dialog == LocalNetworkDialogKind.Settings) {
                            openAppPermissionSettings()
                        } else {
                            state.onAllow()
                        }
                    }) { Text(confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = state.onDismiss) {
                        Text(stringResource(Res.string.local_network_not_now))
                    }
                },
                modifier = modifier,
            )
        }
    }
}
