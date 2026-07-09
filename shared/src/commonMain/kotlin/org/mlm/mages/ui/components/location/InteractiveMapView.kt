package org.mlm.mages.ui.components.location

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class MapCameraPosition(
    val lat: Double,
    val lon: Double,
)

@Composable
expect fun InteractiveMapView(
    lat: Double?,
    lon: Double?,
    isDark: Boolean,
    onCenterChanged: (MapCameraPosition) -> Unit,
    modifier: Modifier = Modifier,
)
