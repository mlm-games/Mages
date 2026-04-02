 package org.mlm.mages.platform

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

    fun get(): SettingsRepository<AppSettings> {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val dataStore = createSettingsDataStore("mages_settings")
            
            val migrationManager = MigrationManager(dataStore, currentVersion = 2)
                .addMigration(EnumIntToStringMigration())
            
            // Run migration synchronously for desktop
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
        // theme_mode: Int -> String (0=System, 1=Light, 2=Dark)
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

        // language: Int -> String (0=System, 1=English, 2=Spanish)
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

        // presence: Int -> String (0=Online, 1=Offline, 2=Unavailable)
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

        // HideInRoomsMode fields: Int -> String (0=Never, 1=PublicRooms, 2=NonDMs, 3=Always)
        val hideInRoomsFields = listOf(
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

        for (field in hideInRoomsFields) {
            val oldValue = prefs[intPreferencesKey(field)]
            if (oldValue != null) {
                val hideInRoomsStr = when (oldValue) {
                    0 -> "Never"
                    1 -> "PublicRooms"
                    2 -> "NonDMs"
                    3 -> "Always"
                    else -> "Never"
                }
                prefs[stringPreferencesKey(field)] = hideInRoomsStr
                prefs.remove(intPreferencesKey(field))
            }
        }
    }
 }