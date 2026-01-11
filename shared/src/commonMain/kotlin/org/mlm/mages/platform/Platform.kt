package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

expect fun getDeviceDisplayName(): String

expect fun deleteDirectory(path: String): Boolean

@Composable
expect fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme?

expect fun platformEmbeddedElementCallUrlOrNull(): String?

expect fun platformEmbeddedElementCallParentUrlOrNull(): String?