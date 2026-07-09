package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun StaticLocationPicker(
    isSending: Boolean,
    onSendCurrentLocation: () -> Unit,
    onSendPickedLocation: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
