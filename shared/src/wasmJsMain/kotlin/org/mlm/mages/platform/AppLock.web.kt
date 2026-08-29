package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class NoopAppLockController : AppLockController {
    private val _isLocked = MutableStateFlow(false)
    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    override val isAvailable: Boolean = false
    override fun lockNow() {}
    override fun requestUnlock(onResult: (Boolean) -> Unit) { onResult(true) }
}

actual fun createAppLockController(): AppLockController = NoopAppLockController()

@Composable
actual fun BindAppLock(controller: AppLockController, enabled: Boolean, timeoutSeconds: Long) {
}

@Composable
actual fun BindScreenSecurity(enabled: Boolean) {
}
