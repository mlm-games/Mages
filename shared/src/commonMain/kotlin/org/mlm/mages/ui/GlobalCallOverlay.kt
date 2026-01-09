package org.mlm.mages.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.mlm.mages.calls.CallManager
import org.mlm.mages.platform.CallWebViewHost
import kotlin.math.roundToInt

@Composable
fun GlobalCallOverlay(
    callManager: CallManager,
    modifier: Modifier = Modifier
) {
    val call by callManager.call.collectAsState()

    val s = call ?: return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxWidth = constraints.maxWidth.toFloat()
        val maxHeight = constraints.maxHeight.toFloat()

        val isMin = s.minimized

        val pipWidthDp = 220.dp
        val pipHeightDp = 140.dp

        val density = LocalDensity.current
        val pipWidthPx = with(density) { pipWidthDp.toPx() }
        val pipHeightPx = with(density) { pipHeightDp.toPx() }

        var offsetX by remember(s.minimized) { mutableFloatStateOf(s.pipX) }
        var offsetY by remember(s.minimized) { mutableFloatStateOf(s.pipY) }

        // Sync from global state when minimized state changes
        LaunchedEffect(s.pipX, s.pipY) {
            offsetX = s.pipX
            offsetY = s.pipY
        }

        val webViewModifier = if (!isMin) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(pipWidthDp, pipHeightDp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Persist final position to CallManager
                            callManager.movePip(offsetX, offsetY)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Update local state immediately for smooth dragging
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxWidth - pipWidthPx)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxHeight - pipHeightPx)
                        }
                    )
                }
        }

        // WebView
        CallWebViewHost(
            widgetUrl = s.widgetUrl,
            widgetBaseUrl = s.widgetBaseUrl,
            modifier = webViewModifier,
            onMessageFromWidget = { msg -> callManager.onMessageFromWidget(msg) },
            onClosed = { callManager.endCall() },
            onAttachController = { callManager.attachController(it) },
            onMinimizeRequested = { callManager.setMinimized(true) }
        )

        // Full-screen top bar controls
        if (isMin) {
            // Minimized PiP controls - positioned above the PiP window
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            offsetX.roundToInt(),
                            (offsetY - with(density) { 48.dp.toPx() }).roundToInt().coerceAtLeast(0)
                        )
                    }
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { callManager.setMinimized(false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Restore",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { callManager.endCall() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "End call",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}