package org.mlm.mages.ui.components.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime

private fun parseGeoUri(geoUri: String?): Pair<Double, Double>? {
    if (geoUri.isNullOrBlank()) return null
    val cleaned = geoUri.removePrefix("geo:").substringBefore("?").substringBefore(";")
    val parts = cleaned.split(",")
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    return lat to lon
}

private fun formatCoords(lat: Double, lon: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lonDir = if (lon >= 0) "E" else "W"
    fun Double.fixed(digits: Int): String {
        val factor = (1..digits).fold(1L) { acc, _ -> acc * 10L }
        val rounded = kotlin.math.round(kotlin.math.abs(this) * factor).toLong()
        val whole = rounded / factor
        val frac = rounded % factor
        return "$whole.${frac.toString().padStart(digits, '0')}"
    }
    return "${lat.fixed(4)}°$latDir, ${lon.fixed(4)}°$lonDir"
}

@Composable
fun TimelineLocationItem(
    event: MessageEvent,
    isOwnActiveShare: Boolean = false,
    isLive: Boolean = event.liveLocation?.isLive == true,
    onClick: () -> Unit,
    onStopLiveLocation: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (event.eventType != EventType.LiveLocation) return
    val geoUri = event.liveLocation?.geoUri
    val coords = parseGeoUri(geoUri)
    val coordText = coords?.let { formatCoords(it.first, it.second) }
    val body = event.body
    val isBodyGeo = body.startsWith("geo:") || body.startsWith("https://maps") || body.startsWith("http://maps")
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val appSettings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val isDark = when (appSettings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = modifier
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 188.dp),
            ) {
                TimelineLocationMapView(
                    lat = coords?.first,
                    lon = coords?.second,
                    isDark = isDark,
                    modifier = Modifier.matchParentSize(),
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = onClick),
                )

                if (isLive) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Spacer(Modifier.width(Spacing.sm))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBodyGeo && coordText != null) coordText else body,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatTime(event.timestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }

                            if (isOwnActiveShare && onStopLiveLocation != null) {
                                Spacer(Modifier.width(Spacing.sm))
                                IconButton(
                                    onClick = onStopLiveLocation,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Stop sharing",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!isLive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(Spacing.sm))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBodyGeo && coordText != null) coordText else body,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatTime(event.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
