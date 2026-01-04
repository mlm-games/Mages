package org.mlm.mages.di

import androidx.compose.runtime.Composable
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration
import org.mlm.mages.settings.AppSettings

/**
 * Composable wrapper that provides Koin context to the app.
 */
@Composable
fun KoinApp(
    settingsRepository: SettingsRepository<AppSettings>,
    content: @Composable () -> Unit
) {
    KoinApplication(
        configuration = koinConfiguration {
            modules(appModules(settingsRepository))
        },
        content = content
    )
}

/**
 * Initialize Koin for non-Compose contexts (e.g., Android Application class).
 */
fun initKoin(
    settingsRepository: SettingsRepository<AppSettings>,
    additionalModules: List<Module> = emptyList()
) {
    startKoin {
        modules(appModules(settingsRepository) + additionalModules)
    }
}

/**
 * Stop Koin (useful for testing or cleanup).
 */
fun stopKoinApp() {
    stopKoin()
}