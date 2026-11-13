// Web version of app/src/main/java/com/mobiledaw/PhoneTurn.kt. The browser turns the page with the
// phone by itself, so there is no countdown on the web.
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER", "unused")

package com.mobiledaw

import android.content.Context

data class TurnCountdown(val seconds: Int, val toLandscape: Boolean)

class PhoneTurn(
    private val context: Context,
    private val onCountdown: (TurnCountdown?) -> Unit,
    private val onTurn: (landscape: Boolean) -> Unit,
) {
    var landscape = false
    fun start() {}
    fun stop() {}
    fun cancel() {}
}
