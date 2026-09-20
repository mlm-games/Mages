package org.mlm.mages.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
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
    onInsertNewline: () -> Unit,
    onSend: () -> Unit
): Modifier = if (!enabled) this else this
    .onPreInterceptKeyBeforeSoftKeyboard { event -> handleEnterShortcut(event, enterSendsMessage, onInsertNewline, onSend) }
    .onPreviewKeyEvent { event -> handleEnterShortcut(event, enterSendsMessage, onInsertNewline, onSend) }

private fun handleEnterShortcut(
    event: KeyEvent,
    enterSendsMessage: Boolean,
    onInsertNewline: () -> Unit,
    onSend: () -> Unit
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
    val bypassesSend = event.isShiftPressed || event.isCtrlPressed || event.isMetaPressed
    if (enterSendsMessage) {
        if (bypassesSend) onInsertNewline() else onSend()
        return true
    }
    if (bypassesSend || event.isAltPressed) {
        onSend()
        return true
    }
    return false
}
