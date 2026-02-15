package org.mlm.mages.platform

import androidx.compose.ui.Modifier

expect fun Modifier.pasteInterceptor(
    onPasteAttempt: () -> Boolean
): Modifier
