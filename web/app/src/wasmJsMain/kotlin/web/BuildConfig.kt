@file:Suppress("PackageDirectoryMismatch")

package com.mobiledaw

/** Like the APK's generated BuildConfig; the build time comes from the page (see platform.js). */
internal object BuildConfig {
    val BUILD_TIME: Long get() = com.mobiledaw.web.WebBuild.TIME
    const val DEBUG = false
}
