package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mlm.mages.matrix.LiveLocationShare

sealed interface LocationViewerMode {
    data object ViewLive : LocationViewerMode
    data class ViewStatic(val lat: Double, val lon: Double) : LocationViewerMode
    data object PickStatic : LocationViewerMode
}

@Composable
expect fun LiveLocationMapViewer(
    shares: Map<String, LiveLocationShare>,
    avatarPathByUserId: Map<String, String>,
    displayNameByUserId: Map<String, String>,
    onDismiss: () -> Unit,
    mode: LocationViewerMode = LocationViewerMode.ViewLive,
    isCurrentlySharing: Boolean = false,
    onStopSharing: (() -> Unit)? = null,
    isSending: Boolean = false,
    onSendCurrentLocation: (() -> Unit)? = null,
    onSendPickedLocation: ((lat: Double, lon: Double) -> Unit)? = null,
    onCenterOnMyLocation: (() -> Unit)? = null,
    initialLat: Double? = null,
    initialLon: Double? = null,
    modifier: Modifier = Modifier,
)
