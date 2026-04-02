package org.mlm.mages.platform

import android.content.Context
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import io.github.mlmgames.settings.core.managers.Migration
import io.github.mlmgames.settings.core.managers.MigrationManager
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
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
            
            val migrationManager = MigrationManager(dataStore, currentVersion = 2)
                .addMigration(EnumIntToStringMigration())

            runBlocking {
                migrationManager.migrate()
            }

            val repo = SettingsRepository(dataStore, AppSettingsSchema)
            repository = repo
            return repo
        }
    }
}

private class EnumIntToStringMigration : Migration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2

    override suspend fun migrate(prefs: MutablePreferences) {
        val oldThemeMode = prefs[intPreferencesKey("theme_mode")]
        if (oldThemeMode != null) {
            val themeModeStr = when (oldThemeMode) {
                0 -> "System"
                1 -> "Light"
                2 -> "Dark"
                else -> "Dark"
            }
            prefs[stringPreferencesKey("theme_mode")] = themeModeStr
            prefs.remove(intPreferencesKey("theme_mode"))
        }

        val oldLanguage = prefs[intPreferencesKey("language")]
        if (oldLanguage != null) {
            val languageStr = when (oldLanguage) {
                0 -> "System"
                1 -> "English"
                2 -> "Spanish"
                else -> "System"
            }
            prefs[stringPreferencesKey("language")] = languageStr
            prefs.remove(intPreferencesKey("language"))
        }

        val oldPresence = prefs[intPreferencesKey("presence")]
        if (oldPresence != null) {
            val presenceStr = when (oldPresence) {
                0 -> "Online"
                1 -> "Offline"
                2 -> "Unavailable"
                else -> "Online"
            }
            prefs[stringPreferencesKey("presence")] = presenceStr
            prefs.remove(intPreferencesKey("presence"))
        }

        val compactFields = listOf(
            "compact_public_room_membership_events",
            "compact_public_room_profile_change_events",
            "compact_public_room_topic_events",
            "compact_public_room_redacted_events",
            "compact_public_room_room_name_events",
            "compact_public_room_room_avatar_events",
            "compact_public_room_room_encryption_events",
            "compact_public_room_room_pinned_events",
            "compact_public_room_room_power_levels_events",
            "compact_public_room_room_canonical_alias_events",
            "compact_public_room_join_rules_events",
            "compact_public_room_history_visibility_events",
            "compact_public_room_guest_access_events",
            "compact_public_room_server_acl_events",
            "compact_public_room_tombstone_events",
            "compact_public_room_space_child_events",
            "compact_public_room_other_state_events"
        )

        for (field in compactFields) {
            val oldValue = prefs[intPreferencesKey(field)]
            if (oldValue != null) {
                val compactStr = when (oldValue) {
                    0 -> "Never"
                    1 -> "PublicRooms"
                    2 -> "NonDMs"
                    3 -> "Always"
                    else -> "Never"
                }
                prefs[stringPreferencesKey(field)] = compactStr
                prefs.remove(intPreferencesKey(field))
            }
        }
    }
}