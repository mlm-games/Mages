package org.mlm.mages.di

import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.KoinApplication
import org.mlm.mages.settings.AppSettings

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration
import org.mlm.mages.MatrixService

/**
 * Composable wrapper that provides Koin context to the app.
 */
@Composable
fun KoinApp(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>,
    content: @Composable () -> Unit
) {
    KoinApplication(configuration = koinConfiguration(declaration = {
        modules(appModules(service, settingsRepository))
    }), content = {
        content()
    })
}

/**
 * Initialize Koin for non-Compose contexts (e.g., Android Application class).
 */
fun initKoin(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>,
    additionalModules: List<Module> = emptyList()
) {
    startKoin {
        modules(appModules(service, settingsRepository) + additionalModules)
    }
}

/**
 * Stop Koin (useful for testing or cleanup).
 */
fun stopKoinApp() {
    stopKoin()
}