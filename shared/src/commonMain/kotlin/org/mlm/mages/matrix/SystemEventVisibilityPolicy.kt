package org.mlm.mages.matrix

import org.mlm.mages.MessageEvent
import org.mlm.mages.settings.AppSettings

enum class RoomClass {
    Direct,
    PrivateGroup,
    PublicRoom,
}

enum class SystemEventKind {
    Membership,
    ProfileChange,
    Topic,
}

enum class VisibilitySurface {
    Timeline,
    RoomListPreview,
    Notification,
}

enum class HideInRooms {
    None,
    PublicRooms,
    NonDirectRooms,
    AllRooms,
}

fun HideInRooms.matches(roomClass: RoomClass): Boolean = when (this) {
    HideInRooms.None -> false
    HideInRooms.PublicRooms -> roomClass == RoomClass.PublicRoom
    HideInRooms.NonDirectRooms -> roomClass != RoomClass.Direct
    HideInRooms.AllRooms -> true
}

fun RoomProfile.roomClass(): RoomClass = when {
    isDm -> RoomClass.Direct
    isPublic -> RoomClass.PublicRoom
    else -> RoomClass.PrivateGroup
}

fun MessageEvent.systemEventKindOrNull(): SystemEventKind? = when (eventType) {
    EventType.MembershipChange -> SystemEventKind.Membership
    EventType.ProfileChange -> SystemEventKind.ProfileChange
    EventType.RoomTopic -> SystemEventKind.Topic
    else -> null
}

data class SystemEventVisibilityPolicy(
    val timelineMembership: HideInRooms = HideInRooms.PublicRooms,
    val timelineProfileChange: HideInRooms = HideInRooms.PublicRooms,
    val timelineTopic: HideInRooms = HideInRooms.PublicRooms,

    val previewMembership: HideInRooms = HideInRooms.PublicRooms,
    val previewProfileChange: HideInRooms = HideInRooms.PublicRooms,
    val previewTopic: HideInRooms = HideInRooms.PublicRooms,

    val notificationMembership: HideInRooms = HideInRooms.None,
    val notificationProfileChange: HideInRooms = HideInRooms.None,
    val notificationTopic: HideInRooms = HideInRooms.None,
)

fun SystemEventVisibilityPolicy.shouldHide(
    event: MessageEvent,
    roomClass: RoomClass,
    surface: VisibilitySurface,
): Boolean {
    val kind = event.systemEventKindOrNull() ?: return false
    val rule = when (surface) {
        VisibilitySurface.Timeline -> when (kind) {
            SystemEventKind.Membership -> timelineMembership
            SystemEventKind.ProfileChange -> timelineProfileChange
            SystemEventKind.Topic -> timelineTopic
        }
        VisibilitySurface.RoomListPreview -> when (kind) {
            SystemEventKind.Membership -> previewMembership
            SystemEventKind.ProfileChange -> previewProfileChange
            SystemEventKind.Topic -> previewTopic
        }
        VisibilitySurface.Notification -> when (kind) {
            SystemEventKind.Membership -> notificationMembership
            SystemEventKind.ProfileChange -> notificationProfileChange
            SystemEventKind.Topic -> notificationTopic
        }
    }
    return rule.matches(roomClass)
}

data class TimelineVisibilitySettings(
    val membership: HideInRooms,
    val profileChange: HideInRooms,
    val topic: HideInRooms,
)

fun AppSettings.toTimelineVisibilitySettings(): TimelineVisibilitySettings {
    return TimelineVisibilitySettings(
        membership = HideInRooms.entries[compactPublicRoomMembershipEvents],
        profileChange = HideInRooms.entries[compactPublicRoomProfileChangeEvents],
        topic = HideInRooms.entries[compactPublicRoomTopicEvents],
    )
}

fun MessageEvent.isHiddenByTimelineVisibility(
    roomClass: RoomClass,
    settings: TimelineVisibilitySettings,
): Boolean {
    val rule = when (eventType) {
        EventType.MembershipChange -> settings.membership
        EventType.ProfileChange -> settings.profileChange
        EventType.RoomTopic -> settings.topic
        else -> return false
    }
    return rule.matches(roomClass)
}

fun List<MessageEvent>.applyTimelineVisibility(
    roomClass: RoomClass,
    settings: TimelineVisibilitySettings,
): List<MessageEvent> = filterNot {
    it.isHiddenByTimelineVisibility(
        roomClass = roomClass,
        settings = settings,
    )
}
