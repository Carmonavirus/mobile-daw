@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package android.os

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SystemClock {
    fun uptimeMillis(): Long = com.mobiledaw.web.Clock.uptime()
    fun elapsedRealtime(): Long = com.mobiledaw.web.Clock.uptime()
}

class Looper private constructor() {
    companion object {
        private val main = Looper()
        fun getMainLooper(): Looper = main
        fun myLooper(): Looper = main
    }
}

/** Runs posted work on the page's event loop (the web has one thread). */
class Handler(looper: Looper) {
    private val scope = MainScope()
    fun post(r: () -> Unit): Boolean { scope.launch { r() }; return true }
    fun post(r: Runnable): Boolean { scope.launch { r.run() }; return true }
    fun postDelayed(r: () -> Unit, ms: Long): Boolean { scope.launch { delay(ms); r() }; return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean { scope.launch { delay(ms); r.run() }; return true }
    fun removeCallbacksAndMessages(token: Any?) {}
}

fun interface Runnable { fun run() }

object Build {
    object VERSION { const val SDK_INT = 34 }
    object VERSION_CODES { const val O = 26; const val P = 28; const val Q = 29; const val R = 30; const val S = 31; const val TIRAMISU = 33 }
    const val MODEL = "Browser"
    const val MANUFACTURER = "Web"
}
