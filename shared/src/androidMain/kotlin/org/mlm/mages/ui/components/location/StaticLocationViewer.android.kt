package org.mlm.mages.ui.components.location

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode

@Composable
actual fun StaticLocationViewer(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val appSettings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val isDark = when (appSettings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val mapStyleUrl = remember(isDark) {
        if (isDark) "https://tiles.openfreemap.org/styles/dark"
        else "https://tiles.openfreemap.org/styles/liberty"
    }

    val position = remember(lat, lon) { Position(longitude = lon, latitude = lat) }
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = position, zoom = 15.0),
    )
    val pinColor = MaterialTheme.colorScheme.primary
    val strokeColor = MaterialTheme.colorScheme.surface

    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUrl),
            cameraState = cameraState,
        ) {
            val feature = remember(lat, lon) {
                FeatureCollection(
                    features = listOf(
                        Feature(
                            geometry = Point(position),
                            properties = JsonObject(emptyMap()),
                        )
                    )
                )
            }
            val source = rememberGeoJsonSource(GeoJsonData.Features(feature))

            CircleLayer(
                id = "static-location-pin",
                source = source,
                radius = const(10.dp),
                color = const(pinColor),
                strokeWidth = const(4.dp),
                strokeColor = const(strokeColor),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            FilledIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }

            FloatingActionButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString("$lat, $lon"))
                    scope.launch {
                        snackbarHostState.showSnackbar("Coordinates copied")
                    }
                },
                modifier = Modifier
                    .padding(32.dp)
                    .align(Alignment.BottomCenter),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy coordinates",
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )
    }
}
