package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface CallWebViewController {
    fun sendToWidget(message: String)
    fun close()
}

@Composable
expect fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit,
    widgetBaseUrl: String?,
    modifier: Modifier = Modifier,
): CallWebViewController