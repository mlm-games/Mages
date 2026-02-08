package org.mlm.mages.ui.components.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

enum class BannerType {
    ERROR,
    LOADING,
    OFFLINE
}

@Composable
fun StatusBanner(
    message: String?,
    type: BannerType,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier
    ) {
        val containerColor = when (type) {
            BannerType.ERROR, BannerType.OFFLINE -> MaterialTheme.colorScheme.errorContainer
            BannerType.LOADING -> MaterialTheme.colorScheme.secondaryContainer
        }

        val contentColor = when (type) {
            BannerType.ERROR, BannerType.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
            BannerType.LOADING -> MaterialTheme.colorScheme.onSecondaryContainer
        }

        val icon: ImageVector? = when (type) {
            BannerType.ERROR -> Icons.Default.ErrorOutline
            BannerType.OFFLINE -> Icons.Default.CloudOff
            BannerType.LOADING -> null
        }

        Surface(
            color = containerColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (type == BannerType.LOADING) {
                    LoadingIndicator(
                        modifier = Modifier.size(12.dp),
                        color = contentColor
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                }

                Spacer(Modifier.width(Spacing.sm))

                Text(
                    text = message ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            }
        }
    }
}