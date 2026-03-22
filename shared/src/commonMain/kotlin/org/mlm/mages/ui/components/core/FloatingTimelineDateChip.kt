package org.mlm.mages.ui.components.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FloatingTimelineDateChip(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(120)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = tween(120),
        ),
        exit = fadeOut(animationSpec = tween(220)) + scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(220),
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}