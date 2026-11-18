@file:Suppress("unused", "PackageDirectoryMismatch")

package android.content.pm

object PackageManager {
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1
}

class PackageInfo(val versionName: String?, val longVersionCode: Long = 0)

class PackageManagerImpl {
    fun getPackageInfo(name: String, flags: Int): PackageInfo = PackageInfo(com.mobiledaw.web.WebBuild.VERSION)
}
