package org.mlm.mages.platform

import android.content.Context
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.AppSettingsSchema

object SettingsProvider {
    @Volatile
    private var repository: SettingsRepository<AppSettings>? = null

    fun get(context: Context): SettingsRepository<AppSettings> {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val dataStore = createSettingsDataStore(context, "app_settings")
            val repo = SettingsRepository(dataStore, AppSettingsSchema)
            repository = repo
            return repo
        }
    }
}