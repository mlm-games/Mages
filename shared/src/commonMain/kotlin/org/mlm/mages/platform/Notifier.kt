package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import io.github.mlmgames.settings.core.SettingsRepository
import org.mlm.mages.MatrixService
import org.mlm.mages.settings.AppSettings

expect object Notifier {
    fun notifyRoom(title: String, body: String, icon: String? = null)
    fun setCurrentRoom(roomId: String?)
    fun setWindowFocused(focused: Boolean)
    fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean
}

@Composable
expect fun BindLifecycle(service: MatrixService, resetSyncState: Boolean = true)

@Composable
expect fun BindNotifications(service: MatrixService, settingsRepository: SettingsRepository<AppSettings>)

@Composable
expect fun rememberQuitApp(): () -> Unit