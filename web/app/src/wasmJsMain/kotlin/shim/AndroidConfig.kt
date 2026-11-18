@file:Suppress("unused", "PackageDirectoryMismatch")

package android.content.res

class Configuration(
    var screenWidthDp: Int = 400,
    var screenHeightDp: Int = 800,
) {
    val smallestScreenWidthDp: Int get() = minOf(screenWidthDp, screenHeightDp)
    val orientation: Int get() = if (screenWidthDp > screenHeightDp) ORIENTATION_LANDSCAPE else ORIENTATION_PORTRAIT
    companion object {
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
