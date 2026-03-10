@file:OptIn(ExperimentalWasmJsInterop::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package org.mlm.mages.matrix

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.JsAny

@JsName("JSON")
private external object JsJson {
    fun stringify(value: JsAny?): String
}

internal fun JsAny?.toJsonString(): String = JsJson.stringify(this)

internal fun JsAny?.toJsonElement(): JsonElement =
    wasmJson.parseToJsonElement(this.toJsonString())

internal fun JsAny?.toJsonArray(): JsonArray =
    this.toJsonElement() as? JsonArray ?: JsonArray(emptyList())

internal fun JsAny?.toJsonObject(): JsonObject? = this.toJsonElement() as? JsonObject

internal fun JsAny?.toJsonPrimitive(): JsonPrimitive? = this.toJsonElement() as? JsonPrimitive

internal val wasmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

internal suspend fun ensureWasmBridgeReady() {
    @Suppress("UNUSED_VARIABLE")
    val ignored: JsAny? = ensureMagesFfi().await()
}

internal suspend fun createWebMatrixFacade(
    homeserverUrl: String,
    baseStoreDir: String,
    accountId: String?,
): WebMatrixFacade {
    val created: JsAny? = WebMatrixFacade.create(homeserverUrl, baseStoreDir, accountId).await()
    return asWebMatrixFacade(created)
}
