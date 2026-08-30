package org.mlm.mages.ui.components.location

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.mlm.mages.platform.LiveLocationProvider
import org.mlm.mages.platform.LocationResult
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun StaticLocationPicker(
    isSending: Boolean,
    onSendCurrentLocation: () -> Unit,
    onSendPickedLocation: (lat: Double, lon: Double) -> Unit,
    onCenterOnMyLocation: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val pickedLatState = remember { mutableStateOf(0.0) }
    val pickedLonState = remember { mutableStateOf(0.0) }

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

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = 133.209639, latitude = -25.947028),
            zoom = 2.0,
        )
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var isCentering by remember { mutableStateOf(false) }

    val cameraPos by remember { derivedStateOf<Position> { cameraState.position.target } }
    LaunchedEffect(cameraPos) {
        pickedLatState.value = cameraPos.latitude
        pickedLonState.value = cameraPos.longitude
    }

    Box(modifier = modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUrl),
            cameraState = cameraState,
        )

        // Crosshair tracks the viewport center; the picked location is the camera target.
        Icon(
            Icons.Default.LocationOn,
            contentDescription = "Picked location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(48.dp)
                .align { size, space, _ ->
                    IntOffset(
                        x = (space.width - size.width) / 2,
                        y = space.height / 2 - size.height,
                    )
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Share Location") },
                    navigationIcon = {
                        FilledIconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        FilledIconButton(
                            onClick = {
                                if (onCenterOnMyLocation != null) {
                                    onCenterOnMyLocation()
                                } else {
                                    scope.launch {
                                        isCentering = true
                                        val result = LiveLocationProvider().getCurrentLocation()
                                        if (result is LocationResult.Success) {
                                            cameraState.animateTo(
                                                CameraPosition(
                                                    target = Position(result.location.longitude, result.location.latitude),
                                                    zoom = 15.0
                                                )
                                            )
                                        }
                                        isCentering = false
                                    }
                                }
                            },
                            enabled = !isCentering
                        ) {
                            if (isCentering) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                )

                Spacer(Modifier.weight(1f))

                if (isSending) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Button(
                        onClick = { onSendPickedLocation(pickedLatState.value, pickedLonState.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Default.LocationOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send this location")
                    }
                }
            }
        }
    }
}
