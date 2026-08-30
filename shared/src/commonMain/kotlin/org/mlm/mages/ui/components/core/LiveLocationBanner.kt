package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.banner_is_sharing
import mages.shared.generated.resources.banner_people_sharing

@Composable
fun LiveLocationBanner(
    shares: Map<String, LiveLocationShare>,
    avatarPathByUserId: Map<String, String>,
    displayNameByUserId: Map<String, String>,
    myUserId: String?,
    onViewAll: () -> Unit,
    onStopSharing: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (shares.isEmpty()) return
    
    val activeShares = shares.values.filter { it.isLive }
    if (activeShares.isEmpty()) return

    val isMeSharing = myUserId != null && activeShares.any { it.userId == myUserId }
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Spacer(Modifier.width(Spacing.sm))
            
            // Show overlapping avatars
            Row {
                activeShares.take(3).forEachIndexed { index, share ->
                    Avatar(
                        name = share.userId,
                        avatarPath = avatarPathByUserId[share.userId],
                        size = 24.dp,
                        modifier = Modifier.offset(x = (-8 * index).dp)
                    )
                }
            }
            
            Spacer(Modifier.width(Spacing.sm))
            
            Text(
                when (activeShares.size) {
                    1 -> stringResource(Res.string.banner_is_sharing, displayNameByUserId[activeShares[0].userId] ?: formatDisplayName(activeShares[0].userId))
                    else -> stringResource(Res.string.banner_people_sharing, activeShares.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            
            if (isMeSharing && onStopSharing != null) {
                IconButton(onClick = onStopSharing) {
                    Icon(Icons.Default.Stop, "Stop")
                }
            }

            if (currentPlatform == SettingPlatform.ANDROID) {
                IconButton(onClick = onViewAll) {
                    Icon(Icons.Default.Map, "View")
                }
            }
        }
    }
}
