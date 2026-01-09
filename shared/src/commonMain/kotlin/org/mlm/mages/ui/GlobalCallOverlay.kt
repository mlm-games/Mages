package org.mlm.mages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

        val density = LocalDensity.current

        var offsetX by remember { mutableFloatStateOf(s.pipX) }
        var offsetY by remember { mutableFloatStateOf(s.pipY) }

        var localPipW by remember { mutableFloatStateOf(s.pipW) }
        var localPipH by remember { mutableFloatStateOf(s.pipH) }

        LaunchedEffect(s.minimized) {
            offsetX = s.pipX
            offsetY = s.pipY
            localPipW = s.pipW
            localPipH = s.pipH
        }

        val pipWidthPx by remember { derivedStateOf { with(density) { localPipW.dp.toPx() } } }
        val pipHeightPx by remember { derivedStateOf { with(density) { localPipH.dp.toPx() } } }

        val webViewModifier = if (!isMin) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(localPipW.dp, localPipH.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(maxWidth, maxHeight) {
                    detectDragGestures(
                        onDragEnd = {
                            callManager.movePip(offsetX, offsetY)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val currentW = localPipW.dp.toPx()
                            val currentH = localPipH.dp.toPx()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxWidth - currentW)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxHeight - currentH)
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
            onMinimizeRequested = { callManager.setMinimized(true) },
            onAttachController = { callManager.attachController(it) }
        )

        // Minimized PiP controls
        if (isMin) {
            val controlBarOffsetY = (offsetY - with(density) { 48.dp.toPx() }).roundToInt().coerceAtLeast(0)

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), controlBarOffsetY) }
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
//                    TODO: Need to insert toWidget event
//                    IconButton(
//                        onClick = { callManager.endCall() },
//                        modifier = Modifier.size(32.dp)
//                    ) {
//                        Icon(
//                            Icons.Default.CallEnd,
//                            contentDescription = "End call",
//                            tint = MaterialTheme.colorScheme.error,
//                            modifier = Modifier.size(18.dp)
//                        )
//                    }
                }
            }

            // Resize handle
            val handleSize = 24.dp
            val handleSizePx = with(density) { handleSize.toPx() }
            val minWPx = with(density) { 160.dp.toPx() }
            val minHPx = with(density) { 100.dp.toPx() }
            val maxWPx = maxWidth * 0.9f
            val maxHPx = maxHeight * 0.9f

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (offsetX + pipWidthPx - handleSizePx).roundToInt(),
                            (offsetY + pipHeightPx - handleSizePx).roundToInt()
                        )
                    }
                    .size(handleSize)
                    .clip(RoundedCornerShape(bottomEnd = 16.dp))
                    .background(Color.White.copy(alpha = 0.3f))
                    .pointerInput(density, maxWidth, maxHeight) { // Stable keys only
                        detectDragGestures(
                            onDragEnd = {
                                callManager.resizePip(localPipW, localPipH)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val currentWPx = localPipW.dp.toPx()
                                val currentHPx = localPipH.dp.toPx()

                                val newWPx = (currentWPx + dragAmount.x).coerceIn(minWPx, maxWPx)
                                val newHPx = (currentHPx + dragAmount.y).coerceIn(minHPx, maxHPx)

                                localPipW = newWPx.toDp().value
                                localPipH = newHPx.toDp().value
                            }
                        )
                    }
            )
        }
    }
}