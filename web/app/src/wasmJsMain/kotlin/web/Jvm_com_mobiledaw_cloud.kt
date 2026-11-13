// JVM built-ins that the app's code in package com.mobiledaw.cloud uses without an import, for the web.
@file:Suppress("unused", "PackageDirectoryMismatch")

package com.mobiledaw.cloud

internal val kotlinx.coroutines.Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Default

internal object System {
    fun currentTimeMillis(): Long = com.mobiledaw.web.Clock.now()
    fun nanoTime(): Long = com.mobiledaw.web.Clock.nanos()
}
