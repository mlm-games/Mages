package org.mlm.mages.notifications

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.inherited_from_space
import mages.shared.generated.resources.inherited_from_space_generic
import org.jetbrains.compose.resources.getString
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.SpaceParentInfo
import org.mlm.mages.settings.AppSettings

@Serializable
data class SpaceNotificationScope(
    val mode: RoomNotificationMode,
    val rooms: List<String> = emptyList(),
)

class SpaceNotificationScopeStore(
    private val settings: SettingsRepository<AppSettings>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(accountId: String?, spaceId: String): SpaceNotificationScope? =
        read(settings.flow.first().spaceNotificationScopesJson)[accountKey(accountId)]?.get(spaceId)

    suspend fun put(accountId: String?, spaceId: String, scope: SpaceNotificationScope) {
        settings.update { current ->
            val all = read(current.spaceNotificationScopesJson)
            val key = accountKey(accountId)
            val account = all[key].orEmpty()
            val updatedAll = all + (key to (account + (spaceId to scope)))
            current.copy(
                spaceNotificationScopesJson = json.encodeToString(updatedAll),
            )
        }
    }

    suspend fun owner(
        accountId: String?,
        spaceIds: List<String>,
        roomId: String,
        mode: RoomNotificationMode?,
    ): String? {
        if (spaceIds.isEmpty()) return null
        val account = read(settings.flow.first().spaceNotificationScopesJson)[accountKey(accountId)]
            ?: return null
        return spaceIds.firstOrNull { spaceId ->
            val scope = account[spaceId] ?: return@firstOrNull false
            roomId in scope.rooms && (mode == null || scope.mode == mode)
        }
    }

    suspend fun disown(accountId: String?, spaceIds: List<String>, roomId: String) {
        if (spaceIds.isEmpty()) return
        settings.update { current ->
            val all = read(current.spaceNotificationScopesJson)
            val key = accountKey(accountId)
            val account = all[key] ?: return@update current
            var changed = false
            val updated = account.mapValues { (spaceId, scope) ->
                if (spaceId in spaceIds && roomId in scope.rooms) {
                    changed = true
                    scope.copy(rooms = scope.rooms - roomId)
                } else {
                    scope
                }
            }
            if (!changed) {
                current
            } else {
                current.copy(spaceNotificationScopesJson = json.encodeToString(all + (key to updated)))
            }
        }
    }

    private fun read(raw: String): Map<String, Map<String, SpaceNotificationScope>> =
        if (raw.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString<Map<String, Map<String, SpaceNotificationScope>>>(raw)
            }.getOrElse { emptyMap() }
        }

    private fun accountKey(accountId: String?): String = accountId ?: ""
}

suspend fun spaceInheritanceLabel(
    store: SpaceNotificationScopeStore,
    accountId: String?,
    roomId: String,
    mode: RoomNotificationMode?,
    parents: List<SpaceParentInfo>,
): String? {
    if (mode == null || parents.isEmpty()) return null
    val owner = store.owner(accountId, parents.map { it.spaceId }, roomId, mode) ?: return null
    val name = parents.firstOrNull { it.spaceId == owner }?.name?.takeIf { it.isNotBlank() }
    return if (name != null) {
        getString(Res.string.inherited_from_space, name)
    } else {
        getString(Res.string.inherited_from_space_generic)
    }
}
