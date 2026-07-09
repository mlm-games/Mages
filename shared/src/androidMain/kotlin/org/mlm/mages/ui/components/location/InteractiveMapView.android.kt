package org.mlm.mages.ui.components.location

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@Composable
actual fun InteractiveMapView(
    lat: Double?,
    lon: Double?,
    isDark: Boolean,
    onCenterChanged: (MapCameraPosition) -> Unit,
    modifier: Modifier,
) {
    val mapStyleUrl = remember(isDark) {
        if (isDark) "https://tiles.openfreemap.org/styles/dark"
        else "https://tiles.openfreemap.org/styles/liberty"
    }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = 0.0, latitude = 0.0),
            zoom = 2.0,
        )
    )

    LaunchedEffect(lat, lon) {
        if (lat != null && lon != null) {
            cameraState.animateTo(
                CameraPosition(
                    target = Position(longitude = lon, latitude = lat),
                    zoom = 15.0,
                ),
                duration = 300.milliseconds,
            )
        }
    }

    val cameraPos by remember { derivedStateOf { cameraState.position.target } }
    LaunchedEffect(cameraPos) {
        onCenterChanged(MapCameraPosition(lat = cameraPos.latitude, lon = cameraPos.longitude))
    }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUrl),
            cameraState = cameraState,
            options = MapOptions(
                renderOptions = RenderOptions(
                    renderMode = RenderOptions.RenderMode.TextureView,
                )
            ),
        )
    }
}
