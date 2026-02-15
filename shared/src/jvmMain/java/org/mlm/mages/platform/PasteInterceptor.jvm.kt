package org.mlm.mages.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

actual fun Modifier.pasteInterceptor(
    onPasteAttempt: () -> Boolean
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        event.key == Key.V &&
        (event.isCtrlPressed || event.isMetaPressed)
    ) {
        onPasteAttempt()
    } else false
}
