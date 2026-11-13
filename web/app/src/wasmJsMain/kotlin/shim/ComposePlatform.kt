@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Android's LocalContext: on the web, the one app-wide context. */
val LocalContext = staticCompositionLocalOf<android.content.Context> { com.mobiledaw.web.WebApp.context }

/** Android's LocalConfiguration, filled from the browser window's size (see Main.kt). */
val LocalConfiguration = staticCompositionLocalOf { android.content.res.Configuration() }
