package org.mlm.mages.matrix

import org.mlm.mages.MessageEvent
import org.mlm.mages.settings.AppSettings

enum class RoomClass {
    Direct,
    PrivateGroup,
    PublicRoom,
}

fun RoomProfile.roomClass(): RoomClass = when {
    isDm -> RoomClass.Direct
    isPublic -> RoomClass.PublicRoom
    else -> RoomClass.PrivateGroup
}

enum class HideInRooms {
    None,
    PublicRooms,
    NonDirectRooms,
    AllRooms;

    fun matches(roomClass: RoomClass): Boolean = when (this) {
        None -> false
        PublicRooms -> roomClass == RoomClass.PublicRoom
        NonDirectRooms -> roomClass != RoomClass.Direct
        AllRooms -> true
    }

    companion object {
        fun fromSettingIndex(value: Int): HideInRooms = when (value) {
            1 -> PublicRooms
            2 -> NonDirectRooms
            3 -> AllRooms
            else -> None
        }
    }
}

enum class SystemEventVisibilityKey {
    Redacted,

    MembershipChange,
    ProfileChange,

    RoomName,
    RoomTopic,
    RoomAvatar,
    RoomEncryption,
    RoomPinnedEvents,
    RoomPowerLevels,
    RoomCanonicalAlias,

    RoomJoinRules,
    RoomHistoryVisibility,
    RoomGuestAccess,
    RoomServerAcl,
    RoomTombstone,
    SpaceChild,

    OtherState,
}

data class SystemEventVisibilitySettings(
    val membershipChange: HideInRooms,
    val profileChange: HideInRooms,
    val roomTopic: HideInRooms,
    val redacted: HideInRooms,
    val roomName: HideInRooms,
    val roomAvatar: HideInRooms,
    val roomEncryption: HideInRooms,
    val roomPinnedEvents: HideInRooms,
    val roomPowerLevels: HideInRooms,
    val roomCanonicalAlias: HideInRooms,
    val roomJoinRules: HideInRooms,
    val roomHistoryVisibility: HideInRooms,
    val roomGuestAccess: HideInRooms,
    val roomServerAcl: HideInRooms,
    val roomTombstone: HideInRooms,
    val spaceChild: HideInRooms,
    val otherState: HideInRooms,
) {
    fun ruleFor(key: SystemEventVisibilityKey): HideInRooms = when (key) {
        SystemEventVisibilityKey.Redacted -> redacted
        SystemEventVisibilityKey.MembershipChange -> membershipChange
        SystemEventVisibilityKey.ProfileChange -> profileChange
        SystemEventVisibilityKey.RoomName -> roomName
        SystemEventVisibilityKey.RoomTopic -> roomTopic
        SystemEventVisibilityKey.RoomAvatar -> roomAvatar
        SystemEventVisibilityKey.RoomEncryption -> roomEncryption
        SystemEventVisibilityKey.RoomPinnedEvents -> roomPinnedEvents
        SystemEventVisibilityKey.RoomPowerLevels -> roomPowerLevels
        SystemEventVisibilityKey.RoomCanonicalAlias -> roomCanonicalAlias
        SystemEventVisibilityKey.RoomJoinRules -> roomJoinRules
        SystemEventVisibilityKey.RoomHistoryVisibility -> roomHistoryVisibility
        SystemEventVisibilityKey.RoomGuestAccess -> roomGuestAccess
        SystemEventVisibilityKey.RoomServerAcl -> roomServerAcl
        SystemEventVisibilityKey.RoomTombstone -> roomTombstone
        SystemEventVisibilityKey.SpaceChild -> spaceChild
        SystemEventVisibilityKey.OtherState -> otherState
    }
}

fun AppSettings.toSystemEventVisibilitySettings(): SystemEventVisibilitySettings =
    SystemEventVisibilitySettings(
        membershipChange = HideInRooms.fromSettingIndex(compactPublicRoomMembershipEvents),
        profileChange = HideInRooms.fromSettingIndex(compactPublicRoomProfileChangeEvents),
        roomTopic = HideInRooms.fromSettingIndex(compactPublicRoomTopicEvents),
        redacted = HideInRooms.fromSettingIndex(compactPublicRoomRedactedEvents),
        roomName = HideInRooms.fromSettingIndex(compactPublicRoomRoomNameEvents),
        roomAvatar = HideInRooms.fromSettingIndex(compactPublicRoomRoomAvatarEvents),
        roomEncryption = HideInRooms.fromSettingIndex(compactPublicRoomRoomEncryptionEvents),
        roomPinnedEvents = HideInRooms.fromSettingIndex(compactPublicRoomRoomPinnedEvents),
        roomPowerLevels = HideInRooms.fromSettingIndex(compactPublicRoomRoomPowerLevelsEvents),
        roomCanonicalAlias = HideInRooms.fromSettingIndex(compactPublicRoomRoomCanonicalAliasEvents),
        roomJoinRules = HideInRooms.fromSettingIndex(compactPublicRoomJoinRulesEvents),
        roomHistoryVisibility = HideInRooms.fromSettingIndex(compactPublicRoomHistoryVisibilityEvents),
        roomGuestAccess = HideInRooms.fromSettingIndex(compactPublicRoomGuestAccessEvents),
        roomServerAcl = HideInRooms.fromSettingIndex(compactPublicRoomServerAclEvents),
        roomTombstone = HideInRooms.fromSettingIndex(compactPublicRoomTombstoneEvents),
        spaceChild = HideInRooms.fromSettingIndex(compactPublicRoomSpaceChildEvents),
        otherState = HideInRooms.fromSettingIndex(compactPublicRoomOtherStateEvents),
    )

private fun MessageEvent.systemVisibilityKeys(): List<SystemEventVisibilityKey> {
    val keys = mutableListOf<SystemEventVisibilityKey>()

    if (isRedacted) {
        keys += SystemEventVisibilityKey.Redacted
    }

    when (eventType) {
        EventType.MembershipChange -> keys += SystemEventVisibilityKey.MembershipChange
        EventType.ProfileChange -> keys += SystemEventVisibilityKey.ProfileChange
        EventType.RoomName -> keys += SystemEventVisibilityKey.RoomName
        EventType.RoomTopic -> keys += SystemEventVisibilityKey.RoomTopic
        EventType.RoomAvatar -> keys += SystemEventVisibilityKey.RoomAvatar
        EventType.RoomEncryption -> keys += SystemEventVisibilityKey.RoomEncryption
        EventType.RoomPinnedEvents -> keys += SystemEventVisibilityKey.RoomPinnedEvents
        EventType.RoomPowerLevels -> keys += SystemEventVisibilityKey.RoomPowerLevels
        EventType.RoomCanonicalAlias -> keys += SystemEventVisibilityKey.RoomCanonicalAlias
        EventType.OtherState -> {
            keys += when (stateEventType) {
                "m.room.join_rules" -> SystemEventVisibilityKey.RoomJoinRules
                "m.room.history_visibility" -> SystemEventVisibilityKey.RoomHistoryVisibility
                "m.room.guest_access" -> SystemEventVisibilityKey.RoomGuestAccess
                "m.room.server_acl" -> SystemEventVisibilityKey.RoomServerAcl
                "m.room.tombstone" -> SystemEventVisibilityKey.RoomTombstone
                "m.space.child" -> SystemEventVisibilityKey.SpaceChild
                else -> SystemEventVisibilityKey.OtherState
            }
        }

        else -> Unit
    }

    return keys
}

fun MessageEvent.isHiddenBySystemEventVisibility(
    roomClass: RoomClass,
    settings: SystemEventVisibilitySettings,
): Boolean {
    val keys = systemVisibilityKeys()
    if (keys.isEmpty()) return false
    return keys.any { key -> settings.ruleFor(key).matches(roomClass) }
}

fun List<MessageEvent>.applySystemEventVisibility(
    roomClass: RoomClass,
    settings: SystemEventVisibilitySettings,
): List<MessageEvent> = filterNot { it.isHiddenBySystemEventVisibility(roomClass, settings) }
