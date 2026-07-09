package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun TimelineLocationMapView(
    lat: Double?,
    lon: Double?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
)
