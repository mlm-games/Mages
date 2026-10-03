package org.mlm.mages.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.secondaryClick(onSecondaryClick: (() -> Unit)?): Modifier {
    if (onSecondaryClick == null) return this
    return pointerInput(onSecondaryClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (!event.buttons.isSecondaryPressed) continue
                val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                down.consume()
                onSecondaryClick()
            }
        }
    }
}
