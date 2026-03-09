package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import org.mlm.mages.matrix.LiveLocationShare

@Composable
expect fun LiveLocationMapViewer(
    shares: Map<String, LiveLocationShare>,
    avatarPathByUserId: Map<String, String>,
    displayNameByUserId: Map<String, String>,
    onDismiss: () -> Unit,
    isCurrentlySharing: Boolean = false,
    onStopSharing: (() -> Unit)? = null,
)
