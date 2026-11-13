package com.mobiledaw.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Window width classes, using the standard Material breakpoints:
 * COMPACT under 600dp (phones), MEDIUM 600–839dp (tablet in portrait, small foldables),
 * EXPANDED 840dp and up (tablet in landscape).
 */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** The current window's width class. Updates on rotation, since rotation is handled in place. */
@Composable
fun windowWidthClass(): WindowWidthClass {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width >= 840 -> WindowWidthClass.EXPANDED
        width >= 600 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.COMPACT
    }
}
