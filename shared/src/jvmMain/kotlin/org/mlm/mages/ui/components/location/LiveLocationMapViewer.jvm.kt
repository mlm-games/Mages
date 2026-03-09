package org.mlm.mages.ui.components.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.ui.theme.Spacing

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

    if (activeShares.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = "Live location map is available on Android only.",
                    style = MaterialTheme.typography.titleLarge,
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(activeShares) { share ->
                        val displayName = displayNameByUserId[share.userId]
                            ?: share.userId.substringAfter("@").substringBefore(":")
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                if (isCurrentlySharing && onStopSharing != null) {
                    Button(
                        onClick = onStopSharing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text("Stop sharing")
                    }
                }
            }
        }

        FilledIconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.lg),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close live location viewer")
        }
    }
}
