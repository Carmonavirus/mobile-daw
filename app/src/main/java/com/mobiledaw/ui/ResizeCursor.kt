package com.mobiledaw.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Shows the two-way left/right resize cursor while a mouse hovers this area (Android with a mouse
 * or trackpad). The web app has its own version (web/app twin/ResizeCursor.kt).
 */
fun Modifier.ewResizeCursor(): Modifier =
    pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW), overrideDescendants = true)
