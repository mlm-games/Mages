package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

internal val ScreenHeaderRadius = 16.dp
private val ScreenHeaderShape = RoundedCornerShape(
    bottomStart = ScreenHeaderRadius,
    bottomEnd = ScreenHeaderRadius,
)

@Composable
internal fun RoundedScreenHeader(
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    bar: @Composable () -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = color, shape = ScreenHeaderShape) {
        Column {
            bar()
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                ),
                content = rows,
            )
        }
    }
}

@Composable
internal fun screenHeaderAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)

@Composable
internal fun ColumnScope.ScreenHeaderProgress(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        LinearWavyProgressIndicator(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ScreenHeaderRadius, vertical = Spacing.xs),
        )
    }
}
