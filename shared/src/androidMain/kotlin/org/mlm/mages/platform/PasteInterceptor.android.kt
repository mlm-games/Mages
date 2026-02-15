package org.mlm.mages.platform

import androidx.compose.ui.Modifier

actual fun Modifier.pasteInterceptor(
    onPasteAttempt: () -> Boolean
): Modifier = this
