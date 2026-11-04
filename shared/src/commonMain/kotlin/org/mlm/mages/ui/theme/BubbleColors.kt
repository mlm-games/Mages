package org.mlm.mages.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

data class BubbleColors(
    val container: Color,
    val content: Color,
)

private const val SENT_SURFACE_MIX = 0.45f

@Composable
@ReadOnlyComposable
fun messageBubbleColors(isMine: Boolean, redacted: Boolean = false): BubbleColors {
    val scheme = MaterialTheme.colorScheme
    return when {
        redacted -> BubbleColors(
            container = scheme.surfaceVariant.copy(alpha = 0.6f),
            content = scheme.onSurfaceVariant,
        )
        isMine -> BubbleColors(
            container = lerp(scheme.primaryContainer, scheme.surface, SENT_SURFACE_MIX),
            content = scheme.onPrimaryContainer,
        )
        else -> BubbleColors(
            container = scheme.surfaceContainerHigh,
            content = scheme.onSurface,
        )
    }
}
