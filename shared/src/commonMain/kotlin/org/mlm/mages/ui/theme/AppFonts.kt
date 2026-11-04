package org.mlm.mages.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.google_sans_flex
import org.jetbrains.compose.resources.Font
import org.mlm.mages.settings.AppFontFamily

@Composable
fun appFontFamily(font: AppFontFamily, width: Float, roundness: Float): FontFamily =
    when (font) {
        AppFontFamily.System -> FontFamily.Default
        AppFontFamily.GoogleSansFlex -> googleSansFlex(width, roundness)
    }

@Composable
private fun googleSansFlex(width: Float, roundness: Float): FontFamily {
    val normal = font(FontWeight.Normal, 400, width, roundness)
    val medium = font(FontWeight.Medium, 500, width, roundness)
    val semibold = font(FontWeight.SemiBold, 600, width, roundness)
    val bold = font(FontWeight.Bold, 700, width, roundness)
    return remember(normal, medium, semibold, bold) {
        FontFamily(normal, medium, semibold, bold)
    }
}

@Composable
private fun font(
    weight: FontWeight,
    axisWeight: Int,
    width: Float,
    roundness: Float,
) = Font(
    Res.font.google_sans_flex,
    weight,
    FontStyle.Normal,
    FontVariation.Settings(
        FontVariation.weight(axisWeight),
        FontVariation.width(width),
        FontVariation.Setting("ROND", roundness),
    ),
)
