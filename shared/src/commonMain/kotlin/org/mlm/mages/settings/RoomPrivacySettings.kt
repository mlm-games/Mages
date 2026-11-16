package org.mlm.mages.settings

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Privacy choices for one room. A null field follows the account setting, so the
 * room only needs an entry once the user overrides something.
 */
@Serializable
data class RoomPrivacyOverride(
    val readReceipts: Boolean? = null,
    val typingIndicators: Boolean? = null,
) {
    val isEmpty: Boolean get() = readReceipts == null && typingIndicators == null
}

private val roomPrivacyCodec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun AppSettings.roomPrivacyOverrides(): Map<String, RoomPrivacyOverride> {
    if (roomPrivacyJson.isBlank()) return emptyMap()
    return try {
        roomPrivacyCodec.decodeFromString<Map<String, RoomPrivacyOverride>>(roomPrivacyJson)
            .filterValues { !it.isEmpty }
    } catch (e: Exception) {
        Logger.w {
            "AppSettings: corrupt roomPrivacyJson (${roomPrivacyJson.length} chars), ignoring: ${e.message}"
        }
        emptyMap()
    }
}

fun AppSettings.readReceiptsForRoom(roomId: String): Boolean =
    roomPrivacyOverrides()[roomId]?.readReceipts ?: sendReadReceipts

fun AppSettings.typingIndicatorsForRoom(roomId: String): Boolean =
    roomPrivacyOverrides()[roomId]?.typingIndicators ?: sendTypingIndicators

fun AppSettings.withRoomPrivacy(
    roomId: String,
    transform: (RoomPrivacyOverride) -> RoomPrivacyOverride,
): AppSettings {
    val overrides = roomPrivacyOverrides().toMutableMap()
    val updated = transform(overrides[roomId] ?: RoomPrivacyOverride())
    if (updated.isEmpty) overrides.remove(roomId) else overrides[roomId] = updated
    return copy(
        roomPrivacyJson = if (overrides.isEmpty()) "" else roomPrivacyCodec.encodeToString(overrides),
    )
}
