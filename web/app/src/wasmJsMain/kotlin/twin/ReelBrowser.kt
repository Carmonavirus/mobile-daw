// Web version of app/src/main/java/com/mobiledaw/sound/ReelBrowser.kt: a browser page can't open
// Instagram behind the scenes, so there is no second way on the web.
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER")

package com.mobiledaw.sound

import android.content.Context

object ReelBrowser {
    const val available = false
    suspend fun find(context: Context, code: String): Pair<String?, String> = null to "not available in the web app."
}
