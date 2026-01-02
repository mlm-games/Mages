package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.mlmgames.settings.core.SettingsRepository
import org.mlm.mages.MatrixService
import org.mlm.mages.settings.AppSettings

expect object Notifier {
    fun notifyRoom(title: String, body: String)
    fun setCurrentRoom(roomId: String?)
    fun setWindowFocused(focused: Boolean)
    fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean
}

@Composable
expect fun BindLifecycle(service: MatrixService)

@Composable
expect fun BindNotifications(service: MatrixService, settingsRepository: SettingsRepository<AppSettings>)

@Composable
expect fun rememberQuitApp(): () -> Unit