package com.mobiledaw.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Right mouse button. The browser build's Compose doesn't pass right clicks to the screens, so
 * the page hands them over itself ([RightMouse.dispatch], window pixels) to the topmost screen
 * area registered with [rightMouseTarget]. Touch never gets here.
 */
object RightMouse {
    class Target(var origin: Offset, var visible: Rect, var onPress: (Offset) -> Unit, var onDrag: (Offset) -> Unit, var onRelease: () -> Unit)

    private val targets = ArrayList<Target>()
    private var active: Target? = null

    fun add(t: Target) { targets += t }
    fun remove(t: Target) { targets -= t; if (active === t) active = null }

    /** [phase]: 0 = pressed, 1 = moved while held, 2 = released. */
    fun dispatch(phase: Int, x: Float, y: Float) {
        val p = Offset(x, y)
        when (phase) {
            0 -> {
                active = null
                if (Desktop.dialogOpen || Desktop.shortcutsOpen) return
                active = targets.lastOrNull { it.visible.contains(p) }
                active?.let { it.onPress(p - it.origin) }
            }
            1 -> active?.let { it.onDrag(p - it.origin) }
            else -> {
                active?.onRelease?.invoke()
                active = null
            }
        }
    }
}

/** This area takes right mouse button presses and drags (positions in its own pixels). */
@Composable
fun Modifier.rightMouseTarget(onPress: (Offset) -> Unit, onDrag: (Offset) -> Unit = {}, onRelease: () -> Unit = {}): Modifier {
    val target = remember { RightMouse.Target(Offset.Zero, Rect.Zero, onPress, onDrag, onRelease) }
    target.onPress = onPress
    target.onDrag = onDrag
    target.onRelease = onRelease
    DisposableEffect(target) {
        RightMouse.add(target)
        onDispose { RightMouse.remove(target) }
    }
    return this
        .onGloballyPositioned {
            target.origin = it.positionInWindow()
            target.visible = it.boundsInWindow()
        }
        // Where Compose does pass the right button on (Android with a mouse), take it here.
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
                    down.consume()
                    target.onPress(down.position)
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        target.onDrag(c.position)
                    }
                    target.onRelease()
                }
            }
        }
}

/** Right mouse click (PC / laptop): touch never triggers it. */
@Composable
fun Modifier.onRightClick(action: () -> Unit): Modifier = rightMouseTarget(onPress = { action() })

/**
 * Mouse wheel / trackpad scroll amounts. The browser reports pixels (a wheel notch is about 100,
 * a trackpad gives small steps); Android reports wheel notches (1 per notch).
 */
object Wheel {
    var reportsPixels = false

    /** How far to scroll, in screen pixels, for a scroll delta. */
    fun scrollPx(d: Float, density: Float): Float = if (reportsPixels) d * density else d * 48f * density

    /** Zoom factor for a Ctrl+wheel / pinch delta (up = in). */
    fun zoomFactor(d: Float): Float =
        kotlin.math.exp(-(if (reportsPixels) d * 0.004f else d * 0.15f)).coerceIn(0.5f, 2f)
}
