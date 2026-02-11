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

@Composable
fun LiveLocationSheet(
    isCurrentlySharing: Boolean,
    onStartSharing: (durationMinutes: Int) -> Unit,
    onStopSharing: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDuration by remember { mutableIntStateOf(15) }
    val durations = listOf(
        15 to "15 minutes",
        60 to "1 hour",
        480 to "8 hours"
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
                if (isCurrentlySharing) "Sharing Your Location" else "Share Live Location",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                if (isCurrentlySharing)
                    "Others in this room can see your real-time location"
                else
                    "Let others see your location in real-time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(Spacing.xl))

            if (isCurrentlySharing) {
                Button(
                    onClick = { onStopSharing(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Stop Sharing")
                }
            } else {
                Text(
                    "Share for",
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
                    onClick = { onStartSharing(selectedDuration); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Start Sharing")
                }

                Spacer(Modifier.height(Spacing.md))

                Text(
                    "Your location will be shared with all room members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}