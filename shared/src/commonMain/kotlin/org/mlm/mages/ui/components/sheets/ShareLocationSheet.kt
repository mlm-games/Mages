package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.ui.components.location.InteractiveMapView
import org.mlm.mages.ui.theme.Spacing

@Composable
fun ShareLocationSheet(
    isSending: Boolean,
    onSendCurrentLocation: () -> Unit,
    onSendPickedLocation: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val appSettings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val isDark = when (appSettings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    var pickedLat by remember { mutableDoubleStateOf(0.0) }
    var pickedLon by remember { mutableDoubleStateOf(0.0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Share Location",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                "Choose a location to share",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Spacing.lg))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                InteractiveMapView(
                    lat = pickedLat,
                    lon = pickedLon,
                    isDark = isDark,
                    onCenterChanged = { pos ->
                        pickedLat = pos.lat
                        pickedLon = pos.lon
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = "Pin",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center),
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(Spacing.md))
                Text("Sending…", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(
                    onClick = { onSendCurrentLocation() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.MyLocation, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Send my current location")
                }

                Spacer(Modifier.height(Spacing.md))

                Button(
                    onClick = { onSendPickedLocation(pickedLat, pickedLon) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LocationOn, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Send this location")
                }
            }
        }
    }
}
