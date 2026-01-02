 package org.mlm.mages.platform

import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.AppSettingsSchema

 object SettingsProvider {
    @Volatile
    private var repository: SettingsRepository<AppSettings>? = null

    fun get(): SettingsRepository<AppSettings> {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val dataStore = createSettingsDataStore("mages_settings")
            val repo = SettingsRepository(dataStore, AppSettingsSchema)
            repository = repo
            return repo
        }
    }
}