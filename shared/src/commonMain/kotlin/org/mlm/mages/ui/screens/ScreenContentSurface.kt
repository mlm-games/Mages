package org.mlm.mages.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val ScreenContentRadius = 16.dp

internal val ScreenContentShape = RoundedCornerShape(
    topStart = ScreenContentRadius,
    topEnd = ScreenContentRadius,
)

@Composable
internal fun screenAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
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
