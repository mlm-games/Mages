package org.mlm.mages.ui.components.location

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.koinInject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing

private val userColors = listOf(
    Color(0xFF6750A4),
    Color(0xFF2196F3),
    Color(0xFF4CAF50),
    Color(0xFFFF9800),
    Color(0xFFE91E63),
    Color(0xFF00BCD4),
    Color(0xFF9C27B0),
    Color(0xFFFF5722),
    Color(0xFF607D8B),
    Color(0xFF795548),
)

private fun getColorForIndex(index: Int): Color {
    return userColors[index % userColors.size]
}

private fun String.toGeoUriPositionOrNull(): Position? {
    val coordinates = removePrefix("geo:")
        .substringBefore(';')
        .substringBefore('?')
        .split(',')

    if (coordinates.size < 2) return null

    val latitude = coordinates[0].toDoubleOrNull() ?: return null
    val longitude = coordinates[1].toDoubleOrNull() ?: return null

    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

    return Position(longitude = longitude, latitude = latitude)
}

@Composable
actual fun LiveLocationMapViewer(
    shares: Map<String, LiveLocationShare>,
    avatarPathByUserId: Map<String, String>,
    displayNameByUserId: Map<String, String>,
    onDismiss: () -> Unit,
    mode: LocationViewerMode,
    isCurrentlySharing: Boolean,
    onStopSharing: (() -> Unit)?,
    isSending: Boolean,
    onSendCurrentLocation: (() -> Unit)?,
    onSendPickedLocation: ((lat: Double, lon: Double) -> Unit)?,
    onCenterOnMyLocation: (() -> Unit)?,
    initialLat: Double?,
    initialLon: Double?,
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
    val primaryColor = MaterialTheme.colorScheme.primary

    val isStatic = mode is LocationViewerMode.ViewStatic
    val isPicking = mode is LocationViewerMode.PickStatic
    val isLive = mode is LocationViewerMode.ViewLive
    val activeShares = remember(shares) { shares.values.filter { it.isLive }.toList() }

    val liveFeatures = remember(activeShares) {
        activeShares.mapIndexedNotNull { index, share ->
            val pos = share.geoUri.toGeoUriPositionOrNull() ?: return@mapIndexedNotNull null
            org.maplibre.spatialk.geojson.Feature(
                geometry = Point(pos),
                properties = JsonObject(
                    mapOf(
                        "userId" to JsonPrimitive(share.userId),
                        "colorIndex" to JsonPrimitive(index)
                    )
                )
            )
        }
    }

    val staticPosition = remember(mode) {
        if (mode is LocationViewerMode.ViewStatic) Position(longitude = mode.lon, latitude = mode.lat)
        else null
    }
    val hasAnyContent = liveFeatures.isNotEmpty() || staticPosition != null || isPicking

    if (!hasAnyContent) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val userIdList = remember(activeShares) { activeShares.map { it.userId } }

    val cameraTarget = remember(staticPosition, liveFeatures, isPicking, initialLat, initialLon) {
        if (staticPosition != null) staticPosition
        else if (liveFeatures.isNotEmpty()) {
            val positions = liveFeatures.map { (it.geometry).coordinates }
            val sumLat = positions.sumOf { it.latitude }
            val sumLon = positions.sumOf { it.longitude }
            val count = positions.size
            Position(longitude = sumLon / count, latitude = sumLat / count)
        } else if (initialLat != null && initialLon != null) {
            Position(longitude = initialLon, latitude = initialLat)
        } else {
            Position(longitude = 133.209639, latitude = -25.947028)
        }
    }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = cameraTarget,
            zoom = if (staticPosition != null) 15.0 else 14.0,
        )
    )

    val colorExpression = remember(userIdList, primaryColor) {
        if (userIdList.isEmpty()) const(primaryColor)
        else switch(
            input = Feature["colorIndex"].asNumber(),
            fallback = const(primaryColor),
            cases = userColors.mapIndexed { index, color ->
                case(index, const(color))
            }.toTypedArray()
        )
    }

    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedUserId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUrl),
            cameraState = cameraState,
        ) {
            if (liveFeatures.isNotEmpty()) {
                val liveSource = rememberGeoJsonSource(
                    GeoJsonData.Features(FeatureCollection(liveFeatures))
                )
                CircleLayer(
                    id = "live-location-points",
                    source = liveSource,
                    radius = const(12.dp),
                    color = colorExpression,
                    strokeWidth = const(3.dp),
                    strokeColor = const(Color.White),
                    onClick = { clickedFeatures ->
                        val userId = clickedFeatures.firstOrNull()
                            ?.properties
                            ?.get("userId")
                            ?.let { (it as? JsonPrimitive)?.content }
                        if (userId != null) {
                            selectedUserId = userId
                        }
                        ClickResult.Consume
                    },
                )
            }

            if (staticPosition != null) {
                val staticFeature = remember(staticPosition) {
                    FeatureCollection(
                        features = listOf(
                            org.maplibre.spatialk.geojson.Feature(
                                geometry = Point(staticPosition),
                                properties = JsonObject(emptyMap()),
                            )
                        )
                    )
                }
                val staticSource = rememberGeoJsonSource(GeoJsonData.Features(staticFeature))
                CircleLayer(
                    id = "static-location-pin",
                    source = staticSource,
                    radius = const(10.dp),
                    color = const(primaryColor),
                    strokeWidth = const(4.dp),
                    strokeColor = const(Color.White),
                )
            }
        }

        if (isPicking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }

                if (onCenterOnMyLocation != null) {
                    FilledIconButton(
                        onClick = onCenterOnMyLocation,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 72.dp, top = 16.dp),
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
                    }
                }

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

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSendCurrentLocation?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send my current location")
                    }
                    Button(
                        onClick = {
                            val target = cameraState.position.target
                            onSendPickedLocation?.invoke(target.latitude, target.longitude)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send this location")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close map")
                }

                if (isStatic) {
                    val staticLat = (mode as LocationViewerMode.ViewStatic).lat
                    val staticLon = (mode as LocationViewerMode.ViewStatic).lon
                    FloatingActionButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString("$staticLat, $staticLon"))
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
                } else if (liveFeatures.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { showBottomSheet = true },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "View all locations"
                            )
                            if (activeShares.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeShares.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }

                if (isCurrentlySharing && onStopSharing != null && !showBottomSheet && !isStatic) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        shape = MaterialTheme.shapes.large,
                        color = Color.Transparent,
                        tonalElevation = 4.dp,
                    ) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            Button(
                                onClick = onStopSharing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text("Stop sharing")
                            }
                        }
                    }
                }
            }

            if (isStatic) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp),
                )
            }

            if (showBottomSheet && isLive) {
                ModalBottomSheet(
                    onDismissRequest = { showBottomSheet = false },
                    sheetState = sheetState,
                ) {
                    LiveLocationBottomSheetContent(
                        activeShares = activeShares,
                        userIdList = userIdList,
                        displayNameByUserId = displayNameByUserId,
                        avatarPathByUserId = avatarPathByUserId,
                        onStopSharing = onStopSharing,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveLocationBottomSheetContent(
    activeShares: List<LiveLocationShare>,
    userIdList: List<String>,
    displayNameByUserId: Map<String, String>,
    avatarPathByUserId: Map<String, String>,
    onStopSharing: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xxl),
    ) {
        Text(
            text = "Live Locations",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(Spacing.lg))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            contentPadding = PaddingValues(bottom = Spacing.md),
        ) {
            items(activeShares) { share ->
                val color = getColorForIndex(userIdList.indexOf(share.userId))
                val displayName = displayNameByUserId[share.userId]
                    ?: share.userId.substringAfter("@").substringBefore(":")
                val avatarPath = avatarPathByUserId[share.userId]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )

                    Spacer(Modifier.width(Spacing.md))

                    Avatar(
                        name = displayName,
                        avatarPath = avatarPath,
                        size = 40.dp,
                    )

                    Spacer(Modifier.width(Spacing.md))

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
