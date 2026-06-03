package org.mlm.mages.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GatewayResolver {
    private const val DEFAULT_GATEWAY = "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"

    suspend fun resolveGateway(endpoint: String): String {
        val parts = parseEndpoint(endpoint) ?: return DEFAULT_GATEWAY
        val (protocol, host, port) = parts
        val portSuffix = if (port != -1) ":$port" else ""
        val customUrl = "$protocol://$host$portSuffix/_matrix/push/v1/notify"

        val response = httpGetString(customUrl) ?: return DEFAULT_GATEWAY
        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            val unifiedpush = json["unifiedpush"]?.jsonObject ?: return DEFAULT_GATEWAY
            val gateway = unifiedpush["gateway"]?.jsonPrimitive?.content
            if (gateway == "matrix") customUrl else DEFAULT_GATEWAY
        } catch (_: Exception) {
            DEFAULT_GATEWAY
        }
    }

    private fun parseEndpoint(endpoint: String): Triple<String, String, Int>? {
        val match = Regex("""^(https?)://([^:/]+)(?::(\d+))?""").find(endpoint) ?: return null
        return Triple(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3].toIntOrNull() ?: -1
        )
    }
}
