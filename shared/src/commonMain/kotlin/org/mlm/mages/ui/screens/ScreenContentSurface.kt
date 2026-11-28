package org.mlm.mages.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal val ScreenContentRadius = 16.dp

internal val ScreenContentShape = RoundedCornerShape(
    topStart = ScreenContentRadius,
    topEnd = ScreenContentRadius,
)

internal val ScreenBottomBarShape = RoundedCornerShape(
    topStart = ScreenContentRadius,
    topEnd = ScreenContentRadius,
)

@Composable
internal fun Modifier.screenContentSurface(contentPadding: PaddingValues): Modifier {
    val colors = MaterialTheme.colorScheme
    return this
        .padding(contentPadding)
        .background(colors.surfaceContainer)
        .clip(ScreenContentShape)
        .background(colors.background)
}
