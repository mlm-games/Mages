package org.mlm.mages.push

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ParsedMatrixPush {
    data class Event(val roomId: String, val eventId: String) : ParsedMatrixPush

    data class CountsUpdate(
        val roomId: String?,
        val unread: Int?,
        val mentions: Int?,
        val hasCounts: Boolean,
    ) : ParsedMatrixPush
}

fun extractMatrixPushPayload(raw: String): List<ParsedMatrixPush> {
    if (raw.isBlank()) return emptyList()
    return try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val results = mutableListOf<ParsedMatrixPush>()

        val notification = obj["notification"]?.jsonObject
        if (notification != null) {
            val eid = notification["event_id"]?.jsonPrimitive?.content ?: ""
            val rid = notification["room_id"]?.jsonPrimitive?.content ?: ""

            if (eid.isNotBlank() && rid.isNotBlank()) {
                results += ParsedMatrixPush.Event(rid, eid)
                return results
            }

            if (eid.isBlank()) {
                val counts = notification["counts"]?.jsonObject
                results += ParsedMatrixPush.CountsUpdate(
                    roomId = rid.ifBlank { null },
                    unread = counts?.get("unread")?.jsonPrimitive?.content?.toIntOrNull(),
                    mentions = counts?.get("mentions")?.jsonPrimitive?.content?.toIntOrNull(),
                    hasCounts = counts != null,
                )
                return results
            }
        }

        if (obj.containsKey("event_id") && obj.containsKey("room_id")) {
            val eid = obj["event_id"]?.jsonPrimitive?.content ?: ""
            val rid = obj["room_id"]?.jsonPrimitive?.content ?: ""
            if (eid.isNotBlank() && rid.isNotBlank()) results += ParsedMatrixPush.Event(rid, eid)
        }

        for (k in arrayOf("events", "notifications")) {
            val arr = obj[k]?.jsonArray ?: continue
            for (it in arr) {
                val item = it.jsonObject
                val eid = item["event_id"]?.jsonPrimitive?.content ?: ""
                val rid = item["room_id"]?.jsonPrimitive?.content ?: ""
                if (eid.isNotBlank() && rid.isNotBlank()) results += ParsedMatrixPush.Event(rid, eid)
            }
        }

        results.distinct()
    } catch (e: Throwable) {
        Logger.w { "PushPayloadParser: unparseable payload (${raw.take(200)}): ${e.message}" }
        emptyList()
    }
}
