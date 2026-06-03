package org.mlm.mages.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun extractMatrixPushPayload(raw: String): List<Pair<String, String>> {
    if (raw.isBlank()) return emptyList()
    return try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val pairs = mutableListOf<Pair<String, String>>()

        val notification = obj["notification"]?.jsonObject
        if (notification != null) {
            val eid = notification["event_id"]?.jsonPrimitive?.content ?: ""
            val rid = notification["room_id"]?.jsonPrimitive?.content ?: ""
            if (eid.isNotBlank() && rid.isNotBlank()) {
                pairs += rid to eid
                return pairs
            }
        }

        if (obj.containsKey("event_id") && obj.containsKey("room_id")) {
            val eid = obj["event_id"]?.jsonPrimitive?.content ?: ""
            val rid = obj["room_id"]?.jsonPrimitive?.content ?: ""
            if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
        }

        for (k in arrayOf("events", "notifications")) {
            val arr = obj[k]?.jsonArray ?: continue
            for (it in arr) {
                val item = it.jsonObject
                val eid = item["event_id"]?.jsonPrimitive?.content ?: ""
                val rid = item["room_id"]?.jsonPrimitive?.content ?: ""
                if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
            }
        }

        pairs.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}
