package org.mlm.mages.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.mages.platform.getDynamicColorScheme


object AppColors {
    val Purple80 = Color(0xFFD0BCFF)
    val PurpleGrey80 = Color(0xFFCCC2DC)
    val Pink80 = Color(0xFFEFB8C8)
    val Purple40 = Color(0xFF6650a4)
    val PurpleGrey40 = Color(0xFF625b71)
    val Pink40 = Color(0xFF7D5260)
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Purple80,
    secondary = AppColors.PurpleGrey80,
    tertiary = AppColors.Pink80,

    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),

    surfaceBright = Color(0xFF38393E),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF1E2025),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),

    background = Color(0xFF0F1114),
    onBackground = Color(0xFFE2E2E6),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4)
)

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Purple40,
    secondary = AppColors.PurpleGrey40,
    tertiary = AppColors.Pink40,

    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),

    surfaceBright = Color(0xFFFDF8FF),
    surfaceDim = Color(0xFFDDD8E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),

    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF1C1B1F),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D)
)

val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(letterSpacing = 0.5.sp)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MainTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = getDynamicColorScheme(darkTheme, dynamicColors)
        ?: if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}