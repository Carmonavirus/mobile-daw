package com.mobiledaw.web

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume

// resources/platform.js
private external fun vfsLoadAll(onEntry: (String, Int8Array?, Double) -> Unit, onDone: () -> Unit)
private external fun vfsPut(path: String, bytes: Int8Array?, modified: Double)
private external fun vfsRemove(path: String)

/**
 * The app's files on the web: an in-memory file system, loaded from IndexedDB at start and
 * written back after every change. Paths are absolute ("/files/projects/First beat.json").
 * Directories are stored as entries without bytes.
 */
object Vfs {
    private class Entry(var bytes: ByteArray?, var modified: Long)

    private val entries = HashMap<String, Entry>()

    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun join(parent: String, child: String): String = if (child.startsWith("/")) child else "$parent/$child"

    suspend fun load() = suspendCancellableCoroutine { cont ->
        vfsLoadAll(
            { path, bytes, modified -> entries[path] = Entry(bytes?.toKotlin(), modified.toLong()) },
            { cont.resume(Unit) },
        )
    }

    private fun now() = Clock.now()

    fun exists(path: String) = path == "/" || entries.containsKey(path)
    fun isDir(path: String) = path == "/" || (entries[path]?.let { it.bytes == null } ?: false)
    fun isFile(path: String) = entries[path]?.bytes != null
    fun size(path: String): Long = entries[path]?.bytes?.size?.toLong() ?: 0L
    fun modified(path: String): Long = entries[path]?.modified ?: 0L
    fun read(path: String): ByteArray? = entries[path]?.bytes

    fun touch(path: String, time: Long): Boolean {
        val e = entries[path] ?: return false
        e.modified = time
        vfsPut(path, e.bytes?.toJs(), time.toDouble())
        return true
    }

    fun mkdirs(path: String): Boolean {
        if (path == "/") return false
        if (entries.containsKey(path)) return false
        val parent = path.substringBeforeLast('/')
        if (parent.isNotEmpty() && parent != "/") mkdirs(parent)
        val t = now()
        entries[path] = Entry(null, t)
        vfsPut(path, null, t.toDouble())
        return true
    }

    fun write(path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/')
        if (parent.isNotEmpty() && !exists(parent)) mkdirs(parent)
        val t = now()
        entries[path] = Entry(bytes, t)
        vfsPut(path, bytes.toJs(), t.toDouble())
    }

    fun children(dir: String): List<String> {
        val prefix = if (dir == "/") "/" else "$dir/"
        return entries.keys.filter { it.startsWith(prefix) && it.length > prefix.length && !it.substring(prefix.length).contains('/') }.sorted()
    }

    fun delete(path: String, recursive: Boolean): Boolean {
        if (!entries.containsKey(path)) return false
        val kids = entries.keys.filter { it.startsWith("$path/") }
        if (kids.isNotEmpty() && !recursive) return false
        for (k in kids) {
            entries.remove(k)
            vfsRemove(k)
        }
        entries.remove(path)
        vfsRemove(path)
        return true
    }

    fun rename(from: String, to: String): Boolean {
        if (!entries.containsKey(from)) return false
        if (entries.containsKey(to)) {
            // Like rename() on Android/Linux: a file replaces an existing file (that's how
            // ProjectStore.save writes a project: to a .tmp file, then renamed over the old one).
            if (!isFile(from) || !isFile(to)) return false
            entries.remove(to)
            vfsRemove(to)
        }
        val moved = entries.keys.filter { it == from || it.startsWith("$from/") }
        for (k in moved) {
            val e = entries.remove(k)!!
            vfsRemove(k)
            val nk = to + k.removePrefix(from)
            entries[nk] = e
            vfsPut(nk, e.bytes?.toJs(), e.modified.toDouble())
        }
        return true
    }
}
