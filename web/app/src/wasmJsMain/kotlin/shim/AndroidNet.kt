@file:Suppress("unused", "PackageDirectoryMismatch")

package android.net

/**
 * A file the person picked in the browser. [key] names it in [com.mobiledaw.web.Picked], which
 * holds its bytes; [name] is the original file name.
 */
class Uri(val key: String, val name: String) {
    override fun toString(): String = "picked:$key/$name"
    val lastPathSegment: String get() = name
    val path: String get() = name
    companion object {
        fun parse(s: String): Uri = Uri(s, s.substringAfterLast('/'))
        val EMPTY = Uri("", "")
    }
}
