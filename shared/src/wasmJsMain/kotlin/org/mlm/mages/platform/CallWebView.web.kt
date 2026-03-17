package org.mlm.mages.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "set_always_on_screen",
    "minimize",
    "im.vector.hangup",
)

@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit,
    onMinimizeRequested: () -> Unit,
    minimized: Boolean,
    widgetBaseUrl: String?,
    modifier: Modifier,
    onAttachController: (CallWebViewController?) -> Unit
): CallWebViewController {
    val controller = remember(widgetUrl) {
        object : CallWebViewController {
            override fun sendToWidget(message: String) {
                try {
                    sendToElementCallIframe(message)
                } catch (_: Exception) {
                }
            }

            override fun close() {
                onClosed()
            }
        }
    }

    DisposableEffect(widgetUrl) {
        val containerId = "element-call-container"

        val wrappedOnMessage: (String) -> Unit = { message ->
            val action = extractActionFromMessage(message)
            
            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                sendElementActionResponse(message)
                
                when (action) {
                    "io.element.close", "im.vector.hangup" -> {
                        onClosed()
                    }
                    "minimize" -> {
                        onMinimizeRequested()
                    }
                    else -> {
                        onMessageFromWidget(message)
                    }
                }
            } else {
                onMessageFromWidget(message)
            }
        }

        try {
            createElementCallIframe(
                containerId,
                widgetUrl,
                wrappedOnMessage
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onAttachController(controller)

        onDispose {
            try {
                removeElementCallIframe()
            } catch (_: Exception) { }
            onAttachController(null)
        }
    }

    DisposableEffect(minimized) {
        setElementCallMinimized(minimized)
        onDispose {}
    }

    // Empty Box - iframe is added via JS
    Box(modifier = modifier.fillMaxSize())
    
    return controller
}

private fun extractActionFromMessage(message: String): String? {
    return try {
        val actionRegex = Regex(""""action"\s*:\s*"([^"]+)"""")
        actionRegex.find(message)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}
