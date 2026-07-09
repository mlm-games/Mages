package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun StaticLocationViewer(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
