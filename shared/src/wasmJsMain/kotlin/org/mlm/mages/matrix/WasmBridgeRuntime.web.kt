@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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

internal val verifJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "phase"
}

internal suspend fun ensureWasmBridgeReady() {
    // wasm-bindgen auto-initializes when the module is loaded
}

internal suspend fun createWasmClient(
    homeserverUrl: String,
    baseStoreDir: String,
    accountId: String?,
    proxyUrl: String?,
): WasmClient {
    val created: JsAny? = WasmClient.createAsync(homeserverUrl, baseStoreDir, accountId, proxyUrl).await<JsAny?>()
    return created as WasmClient
}
