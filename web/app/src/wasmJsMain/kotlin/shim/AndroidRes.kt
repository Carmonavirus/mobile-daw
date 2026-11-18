@file:Suppress("unused", "PackageDirectoryMismatch")

package android.content.res

/** Files the app ships with (web/app/src/wasmJsMain/resources/assets, loaded at start). */
class AssetManager {
    fun open(name: String): java.io.InputStream =
        java.io.ByteArrayInputStream(com.mobiledaw.web.Assets.get(name) ?: throw java.io.FileNotFoundException(name))
    fun list(path: String): Array<String> = com.mobiledaw.web.Assets.list(path).toTypedArray()
}
