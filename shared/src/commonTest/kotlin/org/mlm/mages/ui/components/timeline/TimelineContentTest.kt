package org.mlm.mages.ui.components.timeline

import kotlin.test.Test
import kotlin.test.assertIs
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType

private fun event(type: EventType, body: String = "body"): MessageEvent =
    MessageEvent(
        itemId = "1",
        eventId = "\$1",
        roomId = "!r:example.org",
        sender = "@a:example.org",
        body = body,
        timestampMs = 0L,
        eventType = type,
    )

class TimelineContentTest {

    @Test
    fun staticLocationIsNeverSystem() {
        assertIs<TimelineContent.StaticLocation>(
            event(EventType.Location).toTimelineContent()
        )
    }

    @Test
    fun liveLocationIsNeverSystem() {
        assertIs<TimelineContent.LiveLocation>(
            event(EventType.LiveLocation).toTimelineContent()
        )
    }

    @Test
    fun blankLocationIsStillLocation() {
        assertIs<TimelineContent.StaticLocation>(
            event(EventType.Location, body = "").toTimelineContent()
        )
    }

    @Test
    fun blankSystemEventIsHidden() {
        assertIs<TimelineContent.Hidden>(
            event(EventType.RoomTopic, body = "").toTimelineContent()
        )
    }
}
