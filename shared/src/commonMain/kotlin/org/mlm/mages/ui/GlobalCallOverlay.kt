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

        var offsetX by remember(s.minimized) { mutableFloatStateOf(s.pipX) }
        var offsetY by remember(s.minimized) { mutableFloatStateOf(s.pipY) }

        var localPipW by remember(s.minimized) { mutableFloatStateOf(s.pipW) }
        var localPipH by remember(s.minimized) { mutableFloatStateOf(s.pipH) }

        // Sync when it changes externally
        LaunchedEffect(s.pipX, s.pipY) {
            offsetX = s.pipX
            offsetY = s.pipY
        }

        LaunchedEffect(s.pipW, s.pipH) {
            localPipW = s.pipW
            localPipH = s.pipH
        }

        val pipWidthDp = localPipW.dp
        val pipHeightDp = localPipH.dp
        val pipWidthPx = with(density) { pipWidthDp.toPx() }
        val pipHeightPx = with(density) { pipHeightDp.toPx() }

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
                            callManager.movePip(offsetX, offsetY)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val currentPipWidthPx = localPipW.dp.toPx()
                            val currentPipHeightPx = localPipH.dp.toPx()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxWidth - currentPipWidthPx)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxHeight - currentPipHeightPx)
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
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (offsetX + pipWidthPx - with(density) { 24.dp.toPx() }).roundToInt(),
                            (offsetY + pipHeightPx - with(density) { 24.dp.toPx() }).roundToInt()
                        )
                    }
                    .size(24.dp)
                    .clip(RoundedCornerShape(bottomEnd = 16.dp))
                    .background(Color.White.copy(alpha = 0.3f))
                    .pointerInput(localPipW, localPipH) { // Key on dimensions!
                        detectDragGestures(
                            onDragEnd = {
                                callManager.resizePip(localPipW, localPipH)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val minW = 160.dp.toPx()
                                val minH = 100.dp.toPx()
                                val maxW = maxWidth * 0.9f
                                val maxH = maxHeight * 0.9f

                                val currentWPx = localPipW.dp.toPx()
                                val currentHPx = localPipH.dp.toPx()

                                val newWPx = (currentWPx + dragAmount.x).coerceIn(minW, maxW)
                                val newHPx = (currentHPx + dragAmount.y).coerceIn(minH, maxH)

                                localPipW = newWPx.toDp().value
                                localPipH = newHPx.toDp().value
                            }
                        )
                    }
            )
        }
    }
}