package org.mlm.mages.ui.components.timeline

import androidx.compose.runtime.Composable
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType
import org.mlm.mages.ui.components.message.SystemMessageItem

internal sealed interface TimelineContent {
    val event: MessageEvent

    data class Bubble(
        override val event: MessageEvent,
    ) : TimelineContent

    sealed interface Location : TimelineContent

    data class StaticLocation(
        override val event: MessageEvent,
    ) : Location

    data class LiveLocation(
        override val event: MessageEvent,
    ) : Location

    data class System(
        override val event: MessageEvent,
    ) : TimelineContent

    data class Hidden(
        override val event: MessageEvent,
    ) : TimelineContent
}

internal fun MessageEvent.toTimelineContent(): TimelineContent =
    when (eventType) {
        EventType.Message,
        EventType.Poll,
        EventType.Sticker ->
            TimelineContent.Bubble(this)

        EventType.Location ->
            TimelineContent.StaticLocation(this)

        EventType.LiveLocation ->
            TimelineContent.LiveLocation(this)

        EventType.MembershipChange,
        EventType.ProfileChange,
        EventType.RoomName,
        EventType.RoomTopic,
        EventType.RoomAvatar,
        EventType.RoomEncryption,
        EventType.RoomPinnedEvents,
        EventType.RoomPowerLevels,
        EventType.RoomCanonicalAlias,
        EventType.OtherState,
        EventType.CallInvite,
        EventType.CallNotification ->
            if (body.isBlank()) {
                TimelineContent.Hidden(this)
            } else {
                TimelineContent.System(this)
            }
    }

@Composable
internal fun TimelineEventItem(
    item: TimelineContent,
    bubble: @Composable (TimelineContent.Bubble) -> Unit,
    staticLocation: @Composable (TimelineContent.StaticLocation) -> Unit,
    liveLocation: @Composable (TimelineContent.LiveLocation) -> Unit,
) {
    when (item) {
        is TimelineContent.Bubble -> bubble(item)

        is TimelineContent.StaticLocation ->
            staticLocation(item)

        is TimelineContent.LiveLocation ->
            liveLocation(item)

        is TimelineContent.System ->
            SystemMessageItem(item)

        is TimelineContent.Hidden -> Unit
    }
}
