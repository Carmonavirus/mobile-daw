package com.mobiledaw.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** Turns the page's ew-resize cursor on or off (a CSS class on <body>, so Compose can't override it). */
@JsFun(
    """(on) => {
        if (!document.getElementById('daw-ew-style')) {
            const s = document.createElement('style');
            s.id = 'daw-ew-style';
            s.textContent = 'body.daw-ew, body.daw-ew * { cursor: ew-resize !important; }';
            document.head.appendChild(s);
        }
        document.body.classList.toggle('daw-ew', on);
    }"""
)
private external fun setEwCursor(on: Boolean)

/**
 * Web version of the Android file: shows the two-way left/right resize cursor while the mouse is
 * over this area, and keeps it while a press that started here is held (a drag).
 */
fun Modifier.ewResizeCursor(): Modifier = composed {
    DisposableEffect(Unit) { onDispose { setEwCursor(false) } }
    pointerInput(Unit) {
        var inside = false
        var pressed = false
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                when (e.type) {
                    PointerEventType.Enter -> inside = true
                    PointerEventType.Exit -> inside = false
                    PointerEventType.Press -> { pressed = true; inside = true }
                    PointerEventType.Release -> pressed = e.changes.any { it.pressed }
                    else -> {}
                }
                setEwCursor(inside || pressed)
            }
        }
    }
}
