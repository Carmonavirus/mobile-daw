@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.documentfile.provider

import android.net.Uri
import com.mobiledaw.web.Picked

/** A folder the person picked in the browser, or a file in it. */
class DocumentFile private constructor(val uri: Uri, private val treeKey: String, private val rel: String, private val dir: Boolean) {
    val name: String? get() = if (rel.isEmpty()) Picked.folderName(treeKey) else rel.substringAfterLast('/')
    fun getName(): String? = name
    val isDirectory: Boolean get() = dir
    val isFile: Boolean get() = !dir
    fun isDirectory(): Boolean = dir
    fun isFile(): Boolean = !dir
    fun length(): Long = Picked.bytes(uri)?.size?.toLong() ?: 0L
    fun exists(): Boolean = true
    fun canRead(): Boolean = true
    fun getUri(): Uri = uri

    fun listFiles(): Array<DocumentFile> {
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        val paths = Picked.folderPaths(treeKey).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val kids = LinkedHashMap<String, Boolean>()
        for (p in paths) {
            val first = p.substringBefore('/')
            kids[first] = (kids[first] ?: false) || p.contains('/')
        }
        return kids.map { (n, isDir) ->
            val childRel = prefix + n
            DocumentFile(if (isDir) Uri(treeKey, n) else Picked.fileUri(treeKey, childRel), treeKey, childRel, isDir)
        }.toTypedArray()
    }

    fun findFile(name: String): DocumentFile? = listFiles().firstOrNull { it.name == name }

    companion object {
        fun fromTreeUri(context: android.content.Context, uri: Uri): DocumentFile? = DocumentFile(uri, uri.key, "", true)
        fun fromSingleUri(context: android.content.Context, uri: Uri): DocumentFile? = DocumentFile(uri, uri.key, uri.name, false)
    }
}
