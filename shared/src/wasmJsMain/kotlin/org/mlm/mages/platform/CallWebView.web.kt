package org.mlm.mages.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

// Element-specific actions that the SDK doesn't handle
private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "minimize",
    "im.vector.hangup"
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
                } catch (e: Exception) {
                    // Handle error silently
                }
            }

            override fun close() {
                onClosed()
            }
        }
    }

    // Setup iframe
    DisposableEffect(widgetUrl) {
        val containerId = "element-call-container"

        // Create callback that filters Element-specific actions
        val wrappedOnMessage: (String) -> Unit = { message ->
            // Try to extract action from message
            val action = extractActionFromMessage(message)
            
            // Handle Element-specific actions locally
            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                // Send response back to Element Call FIRST (like JVM/Android do)
                sendElementActionResponse(message)
                
                when (action) {
                    "io.element.close", "im.vector.hangup" -> {
                        onClosed()
                    }
                    "minimize" -> {
                        onMinimizeRequested()
                    }
                    else -> {
                        // Forward to SDK
                        onMessageFromWidget(message)
                    }
                }
            } else {
                // Forward to SDK
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
            // Handle error silently
        }

        onAttachController(controller)

        onDispose {
            try {
                removeElementCallIframe()
            } catch (e: Exception) {
                // Handle error silently
            }
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
        // Simple regex to extract action from JSON-like string
        val actionRegex = Regex(""""action"\s*:\s*"([^"]+)"""")
        actionRegex.find(message)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}
