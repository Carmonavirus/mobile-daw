package com.mobiledaw.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Drum kits the user imported. Each kit is copied into the app's own storage
 * (files/kits/<kit name>/...) so projects keep working even if the download is deleted.
 * Only .wav files are imported.
 */
class KitLibrary(private val context: Context) {
    private val filesDir: File = context.filesDir
    private val root = File(filesDir, "kits").apply { mkdirs() }

    class Sound(val path: String, val name: String, val group: String)

    fun kits(): List<String> =
        root.listFiles { f -> f.isDirectory }?.map { it.name }?.sortedBy { it.lowercase() }.orEmpty()

    /** Sounds in a kit, grouped by the sub-folder they came from (e.g. "Kicks", "808s"). */
    fun sounds(kit: String): List<Sound> {
        val dir = File(root, kit)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .map { f ->
                val parent = f.parentFile?.relativeTo(dir)?.path.orEmpty()
                Sound(f.relativeTo(filesDir).path, f.nameWithoutExtension, parent.ifEmpty { kit })
            }
            .sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
            .toList()
    }

    fun file(path: String) = File(filesDir, path)

    fun deleteKit(kit: String) {
        File(root, kit).deleteRecursively()
    }

    /** Imports every .wav inside a .zip. Returns the kit name, or null if it held no sounds. */
    fun importZip(uri: Uri): String? {
        val kit = uniqueKitName(displayName(uri)?.substringBeforeLast('.') ?: "Kit")
        val dir = File(root, kit)
        val canonicalDir = dir.canonicalPath + File.separator
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !isWanted(name)) continue
                    val target = File(dir, name)
                    // Refuse entries that would escape the kit folder ("zip slip").
                    if (!target.canonicalPath.startsWith(canonicalDir)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        }
        return finish(dir, kit, count)
    }

    /** Imports every .wav inside a folder the user picked (and its sub-folders). */
    fun importFolder(treeUri: Uri): String? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val kit = uniqueKitName(tree.name ?: "Kit")
        val dir = File(root, kit)
        var count = 0
        fun walk(doc: DocumentFile, into: File) {
            for (child in doc.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (!name.startsWith(".")) walk(child, File(into, safe(name)))
                } else if (isWanted(name)) {
                    into.mkdirs()
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(into, safe(name)).outputStream().use { input.copyTo(it) }
                        count++
                    }
                }
            }
        }
        walk(tree, dir)
        return finish(dir, kit, count)
    }

    private fun finish(dir: File, kit: String, count: Int): String? {
        if (count == 0) {
            dir.deleteRecursively()
            return null
        }
        // Many kit zips wrap everything in one folder named after the kit; flatten that level.
        val children = dir.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            inner.delete()
        }
        return kit
    }

    private fun isWanted(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(".wav", ignoreCase = true) &&
            !name.startsWith(".") &&
            !path.contains("__MACOSX")
    }

    private fun safe(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun uniqueKitName(base: String): String {
        val clean = safe(base).trim().ifEmpty { "Kit" }
        if (!File(root, clean).exists()) return clean
        var i = 2
        while (File(root, "$clean $i").exists()) i++
        return "$clean $i"
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
