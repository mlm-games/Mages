package org.mlm.mages.ui.components.location

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@Composable
actual fun TimelineLocationMapView(
    lat: Double?,
    lon: Double?,
    isDark: Boolean,
    modifier: Modifier,
) {
    if (lat == null || lon == null) {
        PlaceholderMapContent(isDark = isDark, modifier = modifier)
        return
    }

    val position = remember(lat, lon) { Position(longitude = lon, latitude = lat) }
    val mapStyleUrl = remember(isDark) {
        if (isDark) "https://tiles.openfreemap.org/styles/dark"
        else "https://tiles.openfreemap.org/styles/liberty"
    }
    val pinColor = MaterialTheme.colorScheme.primary
    val strokeColor = Color.White

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = position, zoom = 15.0)
    )

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUrl),
            cameraState = cameraState,
            options = MapOptions(gestureOptions = GestureOptions.AllDisabled),
        ) {
            val feature = remember(lat, lon) {
                FeatureCollection(
                    features = listOf(
                        org.maplibre.spatialk.geojson.Feature(
                            geometry = Point(position),
                            properties = JsonObject(emptyMap()),
                        )
                    )
                )
            }
            val source = rememberGeoJsonSource(GeoJsonData.Features(feature))

            CircleLayer(
                id = "location-pin",
                source = source,
                radius = const(8.dp),
                color = const(pinColor),
                strokeWidth = const(3.dp),
                strokeColor = const(strokeColor),
            )
        }
    }
}

@Composable
private fun PlaceholderMapContent(
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = if (isDark) {
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    } else {
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f),
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(colors = colors),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
