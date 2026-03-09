package org.mlm.mages.ui.components.location

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    isCurrentlySharing: Boolean,
    onStopSharing: (() -> Unit)?,
) {
    val activeShares = remember(shares) { shares.values.filter { it.isLive }.toList() }
    val userIdList = remember(activeShares) { activeShares.map { it.userId } }

    if (activeShares.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val features = remember(activeShares) {
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

    if (features.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val centroid = remember(features) {
        val positions = features.map { (it.geometry).coordinates }
        val sumLat = positions.sumOf { it.latitude }
        val sumLon = positions.sumOf { it.longitude }
        val count = positions.size
        Position(longitude = sumLon / count, latitude = sumLat / count)
    }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = centroid,
            zoom = 14.0,
        )
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    val colorExpression = remember(userIdList, primaryColor) {
        switch(
            input = Feature["colorIndex"].asNumber(),
            fallback = const(primaryColor),
            cases = userColors.mapIndexed { index, color ->
                case(index, const(color))
            }.toTypedArray()
        )
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedUserId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            cameraState = cameraState,
        ) {
            val source = rememberGeoJsonSource(
                GeoJsonData.Features(FeatureCollection(features))
            )

            CircleLayer(
                id = "live-location-points",
                source = source,
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

        FilledIconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close map")
        }

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

        if (isCurrentlySharing && onStopSharing != null && !showBottomSheet) {
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

        if (showBottomSheet) {
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
