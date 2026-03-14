@file:OptIn(ExperimentalWasmJsInterop::class)
@file:JsModule("./wasm/mages_bridge.js")

package org.mlm.mages.platform

import kotlin.js.JsName

@JsName("createElementCallIframe")
internal external fun createElementCallIframe(
    containerId: String,
    widgetUrl: String,
    onMessage: (String) -> Unit
): Boolean

@JsName("sendToElementCallIframe")
internal external fun sendToElementCallIframe(message: String): Boolean

@JsName("sendElementActionResponse")
internal external fun sendElementActionResponse(originalMessage: String): Boolean

@JsName("removeElementCallIframe")
internal external fun removeElementCallIframe()

@JsName("setElementCallMinimized")
internal external fun setElementCallMinimized(minimized: Boolean)
