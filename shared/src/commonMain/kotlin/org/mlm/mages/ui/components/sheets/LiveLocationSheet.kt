package org.mlm.mages.ui.components.sheets

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
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.live_location_active_desc
import mages.shared.generated.resources.live_location_inactive_desc
import mages.shared.generated.resources.live_location_duration_15m
import mages.shared.generated.resources.live_location_duration_1h
import mages.shared.generated.resources.live_location_duration_8h
import mages.shared.generated.resources.live_location_shared_with_members
import mages.shared.generated.resources.live_location_share_for
import mages.shared.generated.resources.live_location_start_sharing
import mages.shared.generated.resources.live_location_starting
import mages.shared.generated.resources.live_location_stop_sharing
import mages.shared.generated.resources.live_location_title_share
import mages.shared.generated.resources.live_location_title_sharing

@Composable
fun LiveLocationSheet(
    isCurrentlySharing: Boolean,
    isLoading: Boolean = false,
    onStartSharing: (durationMinutes: Int) -> Unit,
    onStopSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDuration by remember { mutableIntStateOf(15) }
    val durations = listOf(
        15 to stringResource(Res.string.live_location_duration_15m),
        60 to stringResource(Res.string.live_location_duration_1h),
        480 to stringResource(Res.string.live_location_duration_8h)
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (isCurrentlySharing) Icons.Default.LocationOn else Icons.Default.LocationSearching,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (isCurrentlySharing) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.lg))

            Text(
                stringResource(if (isCurrentlySharing) Res.string.live_location_title_sharing else Res.string.live_location_title_share),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                stringResource(
                    if (isCurrentlySharing) Res.string.live_location_active_desc
                    else Res.string.live_location_inactive_desc
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Spacing.xl))

            if (isCurrentlySharing) {
                Button(
                    onClick = {
                        if (!isLoading) {
                            onStopSharing()
                            onDismiss()
                        }
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(Icons.Default.Stop, null)
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.live_location_stop_sharing))
                }
            } else {
                Text(
                    stringResource(Res.string.live_location_share_for),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(Spacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    durations.forEach { (minutes, label) ->
                        FilterChip(
                            selected = selectedDuration == minutes,
                            onClick = { selectedDuration = minutes },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))

                Button(
                    onClick = {
                        if (!isLoading) {
                            onStartSharing(selectedDuration)
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(Res.string.live_location_starting))
                    } else {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(Res.string.live_location_start_sharing))
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                Text(
                    stringResource(Res.string.live_location_shared_with_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}