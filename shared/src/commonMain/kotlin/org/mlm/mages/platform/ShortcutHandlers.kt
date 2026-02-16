package org.mlm.mages.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type


fun Modifier.pasteInterceptor(
    onPasteAttempt: () -> Boolean
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        event.key == Key.V &&
        (event.isCtrlPressed || event.isMetaPressed)
    ) {
        onPasteAttempt()
    } else false
}


fun Modifier.sendShortcutHandler(
    enabled: Boolean,
    enterSendsMessage: Boolean,
    onSend: () -> Unit
): Modifier = if (!enabled) this else this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
        if (enterSendsMessage) {
            if (event.isShiftPressed) {
                false
            } else {
                onSend()
                true
            }
        } else {
            if (event.isShiftPressed) {
                onSend()
                true
            } else {
                false
            }
        }
    } else {
        false
    }
}
