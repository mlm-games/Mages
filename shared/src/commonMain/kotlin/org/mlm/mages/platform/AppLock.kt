package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

interface AppLockController {
    val isLocked: StateFlow<Boolean>
    val isAvailable: Boolean
    fun lockNow()
    fun requestUnlock(onResult: (Boolean) -> Unit = {})
}

expect fun createAppLockController(): AppLockController

@Composable
expect fun BindAppLock(
    controller: AppLockController,
    enabled: Boolean,
    timeoutSeconds: Long,
)

@Composable
expect fun BindScreenSecurity(enabled: Boolean)
